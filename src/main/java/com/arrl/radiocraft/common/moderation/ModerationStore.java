package com.arrl.radiocraft.common.moderation;

import com.arrl.radiocraft.Radiocraft;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The moderation log's files, all written and read on one thread of its own. Audio frames come in on a bounded queue
 * (a byte copy and an offer is all the voice chat thread pays); if the writer falls behind, frames are dropped (a
 * warning is logged once) and audio is never held up. Everything else (closing a transmission, flagging, exporting,
 * reading audio back, retention) is a task on an unbounded queue, run after the frames already queued.
 * <p>
 * Layout, under the world's radiocraft/moderation folder (times in UTC):
 * <pre>
 * audio/yyyy-MM-dd/HH.rcma   an hour of Opus frames ({@link FrameFile})
 * index/yyyy-MM-dd.jsonl     a day's closed transmissions, one JSON line each ({@link TransmissionRecord#toJson})
 * kept/ID.json, ID.rcma      a flagged transmission and a copy of its audio (ID.ulaw for a tape), kept past retention
 * tapes/UUID-UNTILMS.ulaw    a player tape's recording as it was, saved before it was recorded over
 * exports/                   Ogg Opus files exported for an admin
 * audit/yyyy-MM-dd.jsonl     who played, exported or flagged what, and when
 * </pre>
 */
public final class ModerationStore {

	/** Frames waiting for the writer at most: about 30 s of everyone on the air in a busy server. */
	public static final int QUEUE_FRAMES = 16_384;
	private static final long HOUR_MS = 3_600_000L;
	private static final long DAY_MS = 86_400_000L;
	/** Audit entries stay at least this long, whatever the retention. */
	private static final int AUDIT_MIN_DAYS = 30;
	private static final int AUDIT_KEEP_IN_MEMORY = 20_000;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

	private record OpusItem(long id, long ms, int stream, byte[] data) {}
	private record PcmItem(long id, long ms, int stream, short[] pcm) {}

	/** A line of the audit log: {@code who} did {@code action} to transmission {@code target} at {@code ms}. */
	public record AuditEntry(long ms, @Nullable UUID who, String whoName, String action, long target, String detail) {}

	private final Path root;
	private final Supplier<OpusEncoder> encoders;
	private final BlockingQueue<Object> frames = new ArrayBlockingQueue<>(QUEUE_FRAMES);
	private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
	private final AtomicLong dropped = new AtomicLong();
	private volatile boolean warnedDrop = false;
	private volatile boolean running = false;
	@Nullable private Thread thread;

	/** Closed transmissions in the retention window (and flagged ones), by id: in time order. Read from any thread. */
	final ConcurrentSkipListMap<Long, TransmissionRecord> records = new ConcurrentSkipListMap<>();
	/** Tape recordings saved before being recorded over: recording id to (until ms to file, relative). */
	private final Map<UUID, NavigableMap<Long, String>> tapeArchive = new ConcurrentHashMap<>();
	private final ArrayDeque<AuditEntry> audit = new ArrayDeque<>();
	private volatile long storageBytes = 0L;
	private volatile boolean loaded = false;

	// Writer thread only.
	private long hourStart = Long.MIN_VALUE;
	@Nullable private OutputStream hourOut;
	private String hourFile = "";
	private long hourPosition = 0L;
	private final Map<Long, List<TransmissionRecord.AudioSpan>> spans = new HashMap<>();
	private final Map<Long, Integer> frameCounts = new HashMap<>();
	private final Map<Long, OpusEncoder> pcmEncoders = new HashMap<>();
	private String indexDay = "";
	@Nullable private BufferedWriter indexOut;
	private long lastFlush = 0L;
	private boolean dirty = false;

	/** A store in {@code root}; {@code encoders} makes an Opus encoder for audio that arrives as PCM (null if none can be made). */
	public ModerationStore(Path root, Supplier<OpusEncoder> encoders) {
		this.root = root;
		this.encoders = encoders;
	}

	public Path root() {
		return root;
	}

	// -------------------- LIFECYCLE --------------------

	/** Starts the writer thread and loads the index (in the background). Retention runs once loaded. */
	public synchronized void start(int retentionDays, double capGb) {
		if(running)
			return;
		running = true;
		Thread writer = new Thread(this::run, "RadioCraft moderation writer");
		writer.setDaemon(true);
		thread = writer;
		writer.start();
		task(() -> {
			load(retentionDays);
			retention(retentionDays, capGb);
		});
	}

	public boolean isRunning() {
		return running;
	}

	/** True once the index has been read back from disk. */
	public boolean isLoaded() {
		return loaded;
	}

	/** Writes out everything queued, closes the files and stops the thread (waits a few seconds at most). */
	public void stop() {
		Thread writer;
		synchronized(this) {
			if(!running)
				return;
			running = false;
			writer = thread;
			thread = null;
		}
		if(writer != null) {
			try {
				writer.join(5000L);
			}
			catch(InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void run() {
		while(running || !frames.isEmpty() || !tasks.isEmpty()) {
			try {
				Object item = frames.poll(200L, TimeUnit.MILLISECONDS);
				while(item != null) {
					write(item);
					item = frames.poll();
				}
				Runnable task;
				while((task = tasks.poll()) != null) {
					drainFrames(); // Every frame queued before the task is written first.
					task.run();
				}
				long now = System.currentTimeMillis();
				if(dirty && now - lastFlush >= 1000L)
					flush();
			}
			catch(InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			catch(Throwable t) {
				Radiocraft.LOGGER.error("Moderation log writer", t);
			}
		}
		closeFiles();
	}

	private void drainFrames() {
		Object item;
		while((item = frames.poll()) != null)
			write(item);
	}

	// -------------------- THE HOT PATH --------------------

	/** Queues a copy of an Opus frame of transmission {@code id}. Any thread; never blocks. */
	public void offerOpus(long id, long ms, int stream, byte[] opus) {
		offer(new OpusItem(id, ms, stream, opus.clone()));
	}

	/** Queues a copy of a PCM frame (48 kHz) of transmission {@code id}, encoded by the writer. Any thread; never blocks. */
	public void offerPcm(long id, long ms, int stream, short[] pcm) {
		offer(new PcmItem(id, ms, stream, pcm.clone()));
	}

	private void offer(Object item) {
		if(!running || !frames.offer(item)) {
			dropped.incrementAndGet();
			if(running && !warnedDrop) {
				warnedDrop = true;
				Radiocraft.LOGGER.warn("The moderation log writer is behind: archive audio is being dropped (radio audio itself is not affected)");
			}
		}
	}

	/** Frames dropped because the writer was behind (or stopped) since start. */
	public long droppedFrames() {
		return dropped.get();
	}

	public int queuedFrames() {
		return frames.size();
	}

	public long storageBytes() {
		return storageBytes;
	}

	// -------------------- WRITING (writer thread) --------------------

	private void write(Object item) {
		long id, ms;
		int stream;
		byte[] data;
		if(item instanceof OpusItem opus) {
			id = opus.id();
			ms = opus.ms();
			stream = opus.stream();
			data = opus.data();
		}
		else if(item instanceof PcmItem pcm) {
			OpusEncoder encoder = pcmEncoders.computeIfAbsent(pcm.id(), k -> encoders.get());
			if(encoder == null)
				return; // No voice chat to encode with.
			id = pcm.id();
			ms = pcm.ms();
			stream = pcm.stream();
			data = encoder.encode(pcm.pcm());
		}
		else
			return;
		try {
			ensureHour(ms);
			OutputStream out = hourOut;
			if(out == null)
				return;
			byte[] bytes = FrameFile.encode(new FrameFile.Frame(id, ms, stream, data), hourStart);
			long start = hourPosition;
			out.write(bytes);
			hourPosition += bytes.length;
			dirty = true;
			List<TransmissionRecord.AudioSpan> list = spans.computeIfAbsent(id, k -> new ArrayList<>(1));
			TransmissionRecord.AudioSpan last = list.isEmpty() ? null : list.getLast();
			if(last != null && last.file().equals(hourFile))
				list.set(list.size() - 1, new TransmissionRecord.AudioSpan(hourFile, last.start(), hourPosition));
			else
				list.add(new TransmissionRecord.AudioSpan(hourFile, start, hourPosition));
			frameCounts.merge(id, 1, Integer::sum);
		}
		catch(IOException e) {
			Radiocraft.LOGGER.warn("Could not write moderation audio: {}", e.getMessage());
		}
	}

	/** Opens the hour file {@code ms} falls in (appending to it if it exists), closing the last one. */
	private void ensureHour(long ms) throws IOException {
		long hour = Math.floorDiv(ms, HOUR_MS) * HOUR_MS;
		if(hour == hourStart && hourOut != null)
			return;
		closeHour();
		Instant at = Instant.ofEpochMilli(hour);
		String file = "audio/" + DAY.format(at) + "/" + String.format(java.util.Locale.ROOT, "%02d", at.atZone(ZoneOffset.UTC).getHour()) + ".rcma";
		Path path = root.resolve(file);
		Files.createDirectories(path.getParent());
		boolean fresh = !Files.isRegularFile(path) || Files.size(path) < FrameFile.HEADER_BYTES;
		OutputStream out = new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE, fresh ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND), 1 << 16);
		if(fresh)
			out.write(FrameFile.header(hour));
		hourOut = out;
		hourStart = hour;
		hourFile = file;
		hourPosition = fresh ? FrameFile.HEADER_BYTES : Files.size(path);
	}

	private void closeHour() {
		if(hourOut != null) {
			try {
				hourOut.close();
			}
			catch(IOException e) {
				Radiocraft.LOGGER.warn("Could not close moderation audio: {}", e.getMessage());
			}
		}
		hourOut = null;
		hourStart = Long.MIN_VALUE;
		hourFile = "";
	}

	private void flush() {
		try {
			if(hourOut != null)
				hourOut.flush();
			if(indexOut != null)
				indexOut.flush();
		}
		catch(IOException e) {
			Radiocraft.LOGGER.warn("Could not flush the moderation log: {}", e.getMessage());
		}
		lastFlush = System.currentTimeMillis();
		dirty = false;
	}

	private void closeFiles() {
		closeHour();
		if(indexOut != null) {
			try {
				indexOut.close();
			}
			catch(IOException e) {
				Radiocraft.LOGGER.warn("Could not close the moderation index: {}", e.getMessage());
			}
			indexOut = null;
			indexDay = "";
		}
		pcmEncoders.values().forEach(OpusEncoder::close);
		pcmEncoders.clear();
	}

	// -------------------- TASKS --------------------

	/** Runs {@code task} on the writer thread, after the frames already queued. */
	private void task(Runnable task) {
		tasks.add(task);
	}

	/** Runs {@code work} on the writer thread; the future completes with its result (or its failure). */
	<T> CompletableFuture<T> call(java.util.concurrent.Callable<T> work) {
		CompletableFuture<T> future = new CompletableFuture<>();
		if(!running) {
			future.completeExceptionally(new IllegalStateException("The moderation log is not running"));
			return future;
		}
		task(() -> {
			try {
				future.complete(work.call());
			}
			catch(Throwable t) {
				future.completeExceptionally(t);
			}
		});
		return future;
	}

	/**
	 * Closes transmission {@code snapshot.id()}: once its frames are written, its audio spans are filled in, its line is
	 * added to its day's index and it joins {@link #records}.
	 */
	public void close(TransmissionRecord snapshot) {
		task(() -> {
			List<TransmissionRecord.AudioSpan> list = spans.remove(snapshot.id());
			Integer count = frameCounts.remove(snapshot.id());
			OpusEncoder encoder = pcmEncoders.remove(snapshot.id());
			if(encoder != null)
				encoder.close();
			TransmissionRecord record = new TransmissionRecord(snapshot.id(), snapshot.kind(), snapshot.startMs(), snapshot.endMs(), snapshot.startGameTime(),
					snapshot.dimension(), snapshot.talkers(), snapshot.radioKind(), snapshot.radioPos(), snapshot.position(), snapshot.frequency(), snapshot.band(),
					snapshot.mode(), snapshot.ctcss(), snapshot.hearers(), snapshot.linkedTo(), snapshot.text(), snapshot.packets(), snapshot.cw(),
					list == null ? List.of() : List.copyOf(list), count == null ? 0 : count, snapshot.tapeRecording(), false);
			writeIndex(record);
			records.put(record.id(), record);
		});
	}

	private void writeIndex(TransmissionRecord record) {
		String day = DAY.format(Instant.ofEpochMilli(record.startMs()));
		try {
			if(!day.equals(indexDay) || indexOut == null) {
				if(indexOut != null)
					indexOut.close();
				Path path = root.resolve("index").resolve(day + ".jsonl");
				Files.createDirectories(path.getParent());
				indexOut = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				indexDay = day;
			}
			indexOut.write(record.toJson().toString());
			indexOut.newLine();
			dirty = true;
		}
		catch(IOException e) {
			Radiocraft.LOGGER.warn("Could not write the moderation index: {}", e.getMessage());
		}
	}

	// -------------------- LOADING --------------------

	private void load(int retentionDays) {
		long cutoff = System.currentTimeMillis() - retentionDays * DAY_MS;
		for(Path file : list(root.resolve("index"), ".jsonl")) {
			try {
				for(String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					if(line.isBlank())
						continue;
					try {
						TransmissionRecord record = TransmissionRecord.fromJson(JsonParser.parseString(line).getAsJsonObject());
						if(record.startMs() >= cutoff)
							records.put(record.id(), record);
					}
					catch(RuntimeException e) {
						Radiocraft.LOGGER.warn("Skipping a broken moderation index line in {}", file.getFileName());
					}
				}
			}
			catch(IOException e) {
				Radiocraft.LOGGER.warn("Could not read the moderation index {}: {}", file.getFileName(), e.getMessage());
			}
		}
		for(Path file : list(root.resolve("kept"), ".json")) { // Flagged: after the index, so the kept copy wins.
			try {
				JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				TransmissionRecord record = TransmissionRecord.fromJson(json);
				records.put(record.id(), record);
			}
			catch(IOException | RuntimeException e) {
				Radiocraft.LOGGER.warn("Could not read the flagged transmission {}: {}", file.getFileName(), e.getMessage());
			}
		}
		for(Path file : list(root.resolve("tapes"), ".ulaw")) {
			String name = file.getFileName().toString();
			int dash = name.lastIndexOf('-');
			try {
				UUID recording = UUID.fromString(name.substring(0, dash));
				long until = Long.parseLong(name.substring(dash + 1, name.length() - ".ulaw".length()));
				tapeArchive.computeIfAbsent(recording, k -> new ConcurrentSkipListMap<>()).put(until, "tapes/" + name);
			}
			catch(RuntimeException e) {
				Radiocraft.LOGGER.warn("Skipping the archived tape {}", name);
			}
		}
		long auditCutoff = System.currentTimeMillis() - Math.max(retentionDays, AUDIT_MIN_DAYS) * DAY_MS;
		List<AuditEntry> entries = new ArrayList<>();
		for(Path file : list(root.resolve("audit"), ".jsonl")) {
			try {
				for(String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
					try {
						JsonObject json = JsonParser.parseString(line).getAsJsonObject();
						AuditEntry entry = new AuditEntry(json.get("ms").getAsLong(), json.has("who") ? UUID.fromString(json.get("who").getAsString()) : null,
								json.has("name") ? json.get("name").getAsString() : "", json.get("action").getAsString(), json.has("target") ? json.get("target").getAsLong() : 0L,
								json.has("detail") ? json.get("detail").getAsString() : "");
						if(entry.ms() >= auditCutoff)
							entries.add(entry);
					}
					catch(RuntimeException e) {
						// A torn line: skip it.
					}
				}
			}
			catch(IOException e) {
				Radiocraft.LOGGER.warn("Could not read the moderation audit log {}: {}", file.getFileName(), e.getMessage());
			}
		}
		entries.sort(java.util.Comparator.comparingLong(AuditEntry::ms));
		synchronized(audit) {
			audit.clear();
			for(AuditEntry entry : entries.subList(Math.max(0, entries.size() - AUDIT_KEEP_IN_MEMORY), entries.size()))
				audit.addLast(entry);
		}
		loaded = true;
	}

	private static List<Path> list(Path folder, String suffix) {
		List<Path> out = new ArrayList<>();
		if(!Files.isDirectory(folder))
			return out;
		try(DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*" + suffix)) {
			for(Path path : stream)
				out.add(path);
		}
		catch(IOException e) {
			Radiocraft.LOGGER.warn("Could not list {}: {}", folder, e.getMessage());
		}
		out.sort(null);
		return out;
	}

	// -------------------- RETENTION --------------------

	/** Runs retention and the storage cap now, on the writer thread; completes with the number of files deleted. */
	public CompletableFuture<Integer> purge(int retentionDays, double capGb) {
		return call(() -> retention(retentionDays, capGb));
	}

	private int retention(int retentionDays, double capGb) {
		long now = System.currentTimeMillis();
		long cutoff = now - retentionDays * DAY_MS;
		int deleted = 0;
		List<Retention.FileSpan> hours = audioFiles();
		for(Retention.FileSpan file : Retention.expired(hours, cutoff))
			deleted += delete(file.name());
		List<Retention.FileSpan> days = dayFiles("index");
		for(Retention.FileSpan file : Retention.expired(days, cutoff)) {
			if(root.resolve(file.name()).equals(root.resolve("index").resolve(indexDay + ".jsonl")) && indexOut != null) {
				try {
					indexOut.close();
				}
				catch(IOException ignored) {
					// Deleted next anyway.
				}
				indexOut = null;
				indexDay = "";
			}
			deleted += delete(file.name());
		}
		List<Retention.FileSpan> auditDays = dayFiles("audit");
		long auditCutoff = now - Math.max(retentionDays, AUDIT_MIN_DAYS) * DAY_MS;
		for(Retention.FileSpan file : Retention.expired(auditDays, auditCutoff))
			deleted += delete(file.name());
		for(Map.Entry<UUID, NavigableMap<Long, String>> entry : tapeArchive.entrySet()) {
			NavigableMap<Long, String> old = entry.getValue().headMap(cutoff, false);
			for(String file : old.values())
				deleted += delete(file);
			old.clear();
		}
		tapeArchive.values().removeIf(Map::isEmpty);
		records.values().removeIf(record -> !record.flagged() && record.startMs() < cutoff);
		synchronized(audit) {
			audit.removeIf(entry -> entry.ms() < auditCutoff);
		}

		// The cap: oldest audio hours first (tape copies count towards it but are not cut).
		long tapes = 0L;
		for(NavigableMap<Long, String> files : tapeArchive.values())
			for(String file : files.values())
				tapes += size(root.resolve(file));
		List<Retention.FileSpan> left = audioFiles();
		long cap = (long)(capGb * 1024.0D * 1024.0D * 1024.0D);
		for(Retention.FileSpan file : Retention.overCap(left, tapes, cap, now))
			deleted += delete(file.name());
		storageBytes = folderSize(root);
		if(deleted > 0)
			Radiocraft.LOGGER.info("Moderation log retention: {} files deleted", deleted);
		return deleted;
	}

	private int delete(String relative) {
		Path path = root.resolve(relative);
		if(relative.equals(hourFile))
			closeHour();
		try {
			boolean gone = Files.deleteIfExists(path);
			Path parent = path.getParent();
			if(parent != null && !parent.equals(root) && Files.isDirectory(parent)) {
				try(DirectoryStream<Path> rest = Files.newDirectoryStream(parent)) {
					if(!rest.iterator().hasNext() && parent.getParent() != null && parent.getParent().getFileName().toString().equals("audio"))
						Files.deleteIfExists(parent); // An emptied day of audio.
				}
			}
			return gone ? 1 : 0;
		}
		catch(IOException e) {
			Radiocraft.LOGGER.warn("Could not delete {}: {}", path, e.getMessage());
			return 0;
		}
	}

	/** The hour files under audio/, with the hour each covers. */
	private List<Retention.FileSpan> audioFiles() {
		List<Retention.FileSpan> out = new ArrayList<>();
		Path audio = root.resolve("audio");
		if(!Files.isDirectory(audio))
			return out;
		try(DirectoryStream<Path> days = Files.newDirectoryStream(audio)) {
			for(Path day : days) {
				if(!Files.isDirectory(day))
					continue;
				LocalDate date;
				try {
					date = LocalDate.parse(day.getFileName().toString());
				}
				catch(RuntimeException e) {
					continue;
				}
				for(Path file : list(day, ".rcma")) {
					String name = file.getFileName().toString();
					try {
						int hour = Integer.parseInt(name.substring(0, name.length() - ".rcma".length()));
						long start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + hour * HOUR_MS;
						out.add(new Retention.FileSpan(root.relativize(file).toString().replace('\\', '/'), start, HOUR_MS, size(file)));
					}
					catch(RuntimeException e) {
						// Not one of ours.
					}
				}
			}
		}
		catch(IOException e) {
			Radiocraft.LOGGER.warn("Could not list the moderation audio: {}", e.getMessage());
		}
		return out;
	}

	private List<Retention.FileSpan> dayFiles(String folder) {
		List<Retention.FileSpan> out = new ArrayList<>();
		for(Path file : list(root.resolve(folder), ".jsonl")) {
			String name = file.getFileName().toString();
			try {
				LocalDate date = LocalDate.parse(name.substring(0, name.length() - ".jsonl".length()));
				out.add(new Retention.FileSpan(folder + "/" + name, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), DAY_MS, size(file)));
			}
			catch(RuntimeException e) {
				// Not one of ours.
			}
		}
		return out;
	}

	private static long size(Path path) {
		try {
			return Files.isRegularFile(path) ? Files.size(path) : 0L;
		}
		catch(IOException e) {
			return 0L;
		}
	}

	private static long folderSize(Path folder) {
		if(!Files.isDirectory(folder))
			return 0L;
		try(var walk = Files.walk(folder)) {
			return walk.filter(Files::isRegularFile).mapToLong(ModerationStore::size).sum();
		}
		catch(IOException | java.io.UncheckedIOException e) {
			return 0L;
		}
	}

	// -------------------- READING BACK --------------------

	/** The frames of {@code record}, in order. Writer thread (the hour being written is flushed first). */
	private List<FrameFile.Frame> frames(TransmissionRecord record) throws IOException {
		flush();
		List<FrameFile.Frame> out = new ArrayList<>();
		for(TransmissionRecord.AudioSpan span : record.audio()) {
			Path file = root.resolve(span.file());
			if(Files.isRegularFile(file))
				out.addAll(FrameFile.read(file, span.start(), span.end(), record.id()));
		}
		return out;
	}

	/** The frames of {@code record}, read on the writer thread. */
	public CompletableFuture<List<FrameFile.Frame>> readFrames(TransmissionRecord record) {
		return call(() -> frames(record));
	}

	/** The tape audio a {@link TransmissionKind#TAPE} record points at, as it was then: kept copy, archived copy or the live file. */
	@Nullable
	Path tapeFile(TransmissionRecord record, Path liveFile) {
		Path kept = root.resolve("kept").resolve(record.id() + ".ulaw");
		if(Files.isRegularFile(kept))
			return kept;
		NavigableMap<Long, String> archived = record.tapeRecording() == null ? null : tapeArchive.get(record.tapeRecording());
		if(archived != null) {
			Map.Entry<Long, String> copy = archived.higherEntry(record.startMs());
			if(copy != null)
				return root.resolve(copy.getValue());
		}
		return Files.isRegularFile(liveFile) ? liveFile : null;
	}

	/** Reads a tape's mu-law bytes on the writer thread (empty if gone). */
	public CompletableFuture<byte[]> readTape(TransmissionRecord record, Path liveFile) {
		return call(() -> {
			Path file = tapeFile(record, liveFile);
			return file == null ? new byte[0] : Files.readAllBytes(file);
		});
	}

	// -------------------- TAPES --------------------

	/**
	 * Tape {@code recording}'s file {@code live} is about to be recorded over: copies it into tapes/ first. Called on the
	 * thread that saves the tape (not the writer), before it writes.
	 */
	public void archiveTape(UUID recording, Path live) {
		if(!Files.isRegularFile(live))
			return;
		long until = System.currentTimeMillis();
		String name = "tapes/" + recording + "-" + until + ".ulaw";
		try {
			Path copy = root.resolve(name);
			Files.createDirectories(copy.getParent());
			Files.copy(live, copy, StandardCopyOption.REPLACE_EXISTING);
			tapeArchive.computeIfAbsent(recording, k -> new ConcurrentSkipListMap<>()).put(until, name);
		}
		catch(IOException e) {
			Radiocraft.LOGGER.warn("Could not keep the tape {} before it was recorded over: {}", recording, e.getMessage());
		}
	}

	// -------------------- FLAGS --------------------

	/** Copies {@code id}'s audio (or tape) to kept/ and marks it flagged. Completes with the flagged record, or null if unknown. */
	public CompletableFuture<TransmissionRecord> flag(long id, Path liveTape) {
		return call(() -> {
			TransmissionRecord record = records.get(id);
			if(record == null)
				return null;
			if(record.flagged())
				return record;
			Path kept = root.resolve("kept");
			Files.createDirectories(kept);
			List<TransmissionRecord.AudioSpan> keptSpans = List.of();
			if(record.hasAudio()) {
				List<FrameFile.Frame> list = frames(record);
				Path file = kept.resolve(id + ".rcma");
				long size = FrameFile.HEADER_BYTES;
				try(OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
					out.write(FrameFile.header(record.startMs()));
					for(FrameFile.Frame frame : list) {
						byte[] bytes = FrameFile.encode(frame, record.startMs());
						out.write(bytes);
						size += bytes.length;
					}
				}
				keptSpans = List.of(new TransmissionRecord.AudioSpan("kept/" + id + ".rcma", FrameFile.HEADER_BYTES, size));
			}
			if(record.kind() == TransmissionKind.TAPE) {
				Path tape = tapeFile(record, liveTape);
				if(tape != null)
					Files.copy(tape, kept.resolve(id + ".ulaw"), StandardCopyOption.REPLACE_EXISTING);
			}
			TransmissionRecord flagged = record.withFlagged(true, keptSpans);
			JsonObject json = flagged.toJson();
			json.add("original", record.toJson().get("audio"));
			Files.writeString(kept.resolve(id + ".json"), json.toString(), StandardCharsets.UTF_8);
			records.put(id, flagged);
			return flagged;
		});
	}

	/**
	 * Unflags {@code id}: its kept copy is deleted, and it goes back to the hourly audio if that is still there (or out
	 * of the log, if it is past retention). Completes with the record as it is now, or null if it is gone.
	 */
	public CompletableFuture<TransmissionRecord> unflag(long id, int retentionDays) {
		return call(() -> {
			TransmissionRecord record = records.get(id);
			if(record == null || !record.flagged())
				return record;
			Path kept = root.resolve("kept");
			List<TransmissionRecord.AudioSpan> original = new ArrayList<>();
			try {
				JsonObject json = JsonParser.parseString(Files.readString(kept.resolve(id + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
				if(json.has("original") && json.get("original").isJsonArray())
					for(var element : json.getAsJsonArray("original")) {
						JsonObject s = element.getAsJsonObject();
						TransmissionRecord.AudioSpan span = new TransmissionRecord.AudioSpan(s.get("f").getAsString(), s.get("s").getAsLong(), s.get("e").getAsLong());
						if(Files.isRegularFile(root.resolve(span.file())))
							original.add(span);
					}
			}
			catch(IOException | RuntimeException e) {
				// No original spans to go back to.
			}
			Files.deleteIfExists(kept.resolve(id + ".json"));
			Files.deleteIfExists(kept.resolve(id + ".rcma"));
			Files.deleteIfExists(kept.resolve(id + ".ulaw"));
			if(record.startMs() < System.currentTimeMillis() - retentionDays * DAY_MS) {
				records.remove(id);
				return null;
			}
			TransmissionRecord back = record.withFlagged(false, List.copyOf(original));
			records.put(id, back);
			return back;
		});
	}

	// -------------------- EXPORT --------------------

	/**
	 * Writes {@code record}'s audio to exports/ as Ogg Opus, a file per talker. {@code tapePcm} supplies a tape's audio
	 * (encoded here) for a {@link TransmissionKind#TAPE}. Completes with the files written (none if it had no audio).
	 */
	public CompletableFuture<List<Path>> export(TransmissionRecord record, @Nullable Path liveTape, String label) {
		return call(() -> exportNow(record, liveTape, label));
	}

	private List<Path> exportNow(TransmissionRecord record, @Nullable Path liveTape, String label) throws IOException {
		Path folder = root.resolve("exports");
		Files.createDirectories(folder);
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(record.startMs()));
		String base = stamp + "-" + safe(label) + "-" + record.id();
		Map<Integer, List<OggOpusWriter.TimedPacket>> streams = new TreeMap<>();
		if(record.hasAudio()) {
			for(FrameFile.Frame frame : frames(record))
				streams.computeIfAbsent(frame.stream(), k -> new ArrayList<>()).add(new OggOpusWriter.TimedPacket(frame.epochMs(), frame.data()));
		}
		else if(record.kind() == TransmissionKind.TAPE && liveTape != null) {
			Path tape = tapeFile(record, liveTape);
			OpusEncoder encoder = tape == null ? null : encoders.get();
			if(encoder != null) {
				try {
					short[] pcm = com.arrl.radiocraft.common.radio.tape.TapeRecordings.decode(Files.readAllBytes(tape));
					List<OggOpusWriter.TimedPacket> packets = new ArrayList<>();
					for(int from = 0, i = 0; from < pcm.length; from += 960, i++)
						packets.add(new OggOpusWriter.TimedPacket(record.startMs() + i * 20L, encoder.encode(java.util.Arrays.copyOfRange(pcm, from, from + 960))));
					streams.put(0, packets);
				}
				finally {
					encoder.close();
				}
			}
		}
		List<Path> out = new ArrayList<>();
		for(Map.Entry<Integer, List<OggOpusWriter.TimedPacket>> stream : streams.entrySet()) {
			Path file = folder.resolve(base + (streams.size() > 1 ? "-" + stream.getKey() : "") + ".opus");
			Map<String, String> tags = new LinkedHashMap<>();
			tags.put("TITLE", record.kind().id() + " " + record.id());
			if(stream.getKey() < record.talkers().size())
				tags.put("ARTIST", record.talkers().get(stream.getKey()).name());
			tags.put("DATE", Instant.ofEpochMilli(record.startMs()).toString());
			tags.put("COMMENT", record.radioKind() + " " + String.format(java.util.Locale.ROOT, "%.4f MHz", record.frequency() / 1_000_000.0D) + " " + record.dimension());
			try(OutputStream os = new BufferedOutputStream(Files.newOutputStream(file))) {
				OggOpusWriter.write(os, stream.getValue(), tags, (int)record.id() ^ stream.getKey());
			}
			out.add(file);
		}
		return out;
	}

	private static String safe(String text) {
		String cleaned = text.replaceAll("[^A-Za-z0-9_.-]+", "_");
		return cleaned.isEmpty() ? "radio" : cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
	}

	// -------------------- AUDIT --------------------

	/** Adds a line to the audit log (in memory now, on disk from the writer thread). */
	public void audit(AuditEntry entry) {
		synchronized(audit) {
			audit.addLast(entry);
			while(audit.size() > AUDIT_KEEP_IN_MEMORY)
				audit.removeFirst();
		}
		if(!running)
			return;
		task(() -> {
			JsonObject json = new JsonObject();
			json.addProperty("ms", entry.ms());
			if(entry.who() != null)
				json.addProperty("who", entry.who().toString());
			json.addProperty("name", entry.whoName());
			json.addProperty("action", entry.action());
			json.addProperty("target", entry.target());
			if(!entry.detail().isEmpty())
				json.addProperty("detail", entry.detail());
			Path file = root.resolve("audit").resolve(DAY.format(Instant.ofEpochMilli(entry.ms())) + ".jsonl");
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, json + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			catch(IOException e) {
				Radiocraft.LOGGER.warn("Could not write the moderation audit log: {}", e.getMessage());
			}
		});
	}

	/** Audit lines from {@code fromMs} (inclusive) to {@code toMs} (exclusive), newest first, at most {@code limit}. */
	public List<AuditEntry> auditEntries(long fromMs, long toMs, int limit) {
		List<AuditEntry> out = new ArrayList<>();
		synchronized(audit) {
			var it = audit.descendingIterator();
			while(it.hasNext() && out.size() < limit) {
				AuditEntry entry = it.next();
				if(entry.ms() >= fromMs && entry.ms() < toMs)
					out.add(entry);
			}
		}
		return out;
	}

	/** Every record in memory (closed transmissions in the window, and flagged ones). */
	public Collection<TransmissionRecord> all() {
		return records.values();
	}
}
