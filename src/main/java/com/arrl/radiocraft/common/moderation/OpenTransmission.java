package com.arrl.radiocraft.common.moderation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A transmission still on the air: frames are appended from the voice chat thread or the server thread, hearers are
 * added as receivers hear it, and the sweeper on the server thread closes it after a gap ({@link ModerationLog#GAP_MS}).
 * Appending and closing are under this object's lock, so a frame never lands after its transmission was closed.
 */
final class OpenTransmission {

	/** What a signal is: its own transmissions on one antenna, as receivers tell them apart. */
	enum Channel { VOICE, CW, DATA }

	/** Most data packets or CW buffers kept per transmission; past this they are counted, not kept. */
	static final int MAX_ITEMS = 512;

	final long id;
	final TransmissionKind kind;
	final Object source;
	final Channel channel;
	final float frequency;
	final long startMs;
	final long startGameTime;
	final String dimension;
	final String radioKind;
	@Nullable final BlockPos radioPos;
	@Nullable final BlockPos position;
	final String band;
	final String mode;
	final float ctcss;
	final long linkedTo;
	/** Audio frames go to the store. */
	final boolean keepAudio;
	@Nullable final Level level;
	@Nullable final UUID tapeRecording;

	volatile long lastMs;
	/** When the sweeper last looked for proximity listeners. Server thread. */
	long lastListenerCheck = Long.MIN_VALUE / 2;
	/** Voice ids its frames went out under, so a receiver can tell two transmissions on one antenna apart. */
	final java.util.Set<UUID> voiceIds = ConcurrentHashMap.newKeySet();
	private boolean closed = false;
	private int frames = 0;
	/** Talkers in the order they first spoke: the index is the stream number. Under this object's lock. */
	private final List<UUID> talkers = new ArrayList<>(2);
	final Map<Object, HearerState> hearers = new ConcurrentHashMap<>();
	private final StringBuilder text = new StringBuilder();
	private final List<TransmissionRecord.DataPacket> packets = new ArrayList<>();
	private final LinkedHashSet<Integer> cwIds = new LinkedHashSet<>();
	private final List<TransmissionRecord.CwBuffer> cw = new ArrayList<>();

	OpenTransmission(long id, TransmissionKind kind, Object source, Channel channel, float frequency, long startMs, long startGameTime, String dimension,
			String radioKind, @Nullable BlockPos radioPos, @Nullable BlockPos position, String band, String mode, float ctcss, long linkedTo, boolean keepAudio,
			@Nullable Level level, @Nullable UUID tapeRecording) {
		this.id = id;
		this.kind = kind;
		this.source = source;
		this.channel = channel;
		this.frequency = frequency;
		this.startMs = startMs;
		this.startGameTime = startGameTime;
		this.dimension = dimension;
		this.radioKind = radioKind;
		this.radioPos = radioPos;
		this.position = position;
		this.band = band;
		this.mode = mode;
		this.ctcss = ctcss;
		this.linkedTo = linkedTo;
		this.keepAudio = keepAudio;
		this.level = level;
		this.tapeRecording = tapeRecording;
		this.lastMs = startMs;
	}

	/** One heard station, while the transmission is open. Strength only rises; names are filled in on the server thread. */
	static final class HearerState {
		@Nullable volatile UUID player;
		volatile String playerName = "";
		final String radioKind;
		final String dimension;
		@Nullable final BlockPos pos;
		volatile float strength;
		final long firstMs;
		/** For a block radio with nobody holding it: the level to look for players near its speaker in. */
		@Nullable final Level level;
		/** Players near it have been looked for already, at this ms. */
		volatile long nearbyCheckedMs = Long.MIN_VALUE;

		HearerState(@Nullable UUID player, String radioKind, String dimension, @Nullable BlockPos pos, float strength, long firstMs, @Nullable Level level) {
			this.player = player;
			this.radioKind = radioKind;
			this.dimension = dimension;
			this.pos = pos;
			this.strength = strength;
			this.firstMs = firstMs;
			this.level = level;
		}

		TransmissionRecord.Hearer snapshot() {
			return new TransmissionRecord.Hearer(player, playerName, radioKind, dimension, pos, strength, firstMs);
		}
	}

	synchronized boolean isClosed() {
		return closed;
	}

	/**
	 * Notes a frame from {@code talker} (null for a block) at {@code now}; returns its stream number, or -1 if the
	 * transmission was closed already (the caller opens a new one).
	 */
	synchronized int frame(@Nullable UUID talker, long now) {
		if(closed)
			return -1;
		lastMs = now;
		frames++;
		return stream(talker);
	}

	/** As {@link #frame} for a frame whose audio is kept: offers it to {@code store} under the lock, so it lands before the close. */
	synchronized boolean audio(@Nullable UUID talker, long now, ModerationStore store, byte[] opus, short[] pcm) {
		if(closed)
			return false;
		lastMs = now;
		frames++;
		int stream = stream(talker);
		if(opus != null)
			store.offerOpus(id, now, stream, opus);
		else if(pcm != null)
			store.offerPcm(id, now, stream, pcm);
		return true;
	}

	private int stream(@Nullable UUID talker) {
		if(talker == null)
			return 0;
		int index = talkers.indexOf(talker);
		if(index < 0 && talkers.size() < 255) {
			talkers.add(talker);
			index = talkers.size() - 1;
		}
		return Math.max(0, index);
	}

	/** Adds a data packet and its line of text ({@code raw}: run on, as RTTY characters are). False if closed. */
	synchronized boolean addPacket(TransmissionRecord.DataPacket packet, String line, boolean raw, long now, @Nullable UUID talker) {
		if(closed)
			return false;
		lastMs = now;
		frames++;
		stream(talker);
		if(packets.size() < MAX_ITEMS)
			packets.add(packet);
		if(!line.isEmpty() && text.length() < 2000)
			text.append(text.isEmpty() || raw ? "" : " / ").append(line);
		return true;
	}

	/** Names {@code talker} as one of its senders (a teletype's typist). */
	synchronized void addTalker(UUID talker) {
		if(!closed)
			stream(talker);
	}

	synchronized boolean addCw(Iterable<TransmissionRecord.CwBuffer> buffers, long now, @Nullable UUID talker) {
		if(closed)
			return false;
		lastMs = now;
		frames++;
		stream(talker);
		for(TransmissionRecord.CwBuffer buffer : buffers)
			if(cw.size() < MAX_ITEMS && cwIds.add(buffer.id()))
				cw.add(buffer);
		return true;
	}

	synchronized void appendText(String line) {
		if(!line.isEmpty() && text.length() < 2000)
			text.append(text.isEmpty() ? "" : " ").append(line);
	}

	synchronized List<UUID> talkers() {
		return List.copyOf(talkers);
	}

	synchronized int frames() {
		return frames;
	}

	/** Closes it; true if this call did (false if it was closed already). */
	synchronized boolean close() {
		if(closed)
			return false;
		closed = true;
		return true;
	}

	/** Records a hearer, or raises its strength. Any thread. */
	void hear(Object key, @Nullable UUID player, String hearerKind, String hearerDimension, @Nullable BlockPos pos, double strength, long now, @Nullable Level hearerLevel) {
		HearerState state = hearers.get(key);
		if(state == null) {
			state = hearers.computeIfAbsent(key, k -> new HearerState(player, hearerKind, hearerDimension, pos, (float)strength, now, hearerLevel));
		}
		if(strength > state.strength)
			state.strength = (float)strength;
	}

	/**
	 * The record to keep, closed at {@code endMs}. {@code names} gives a talker's name and callsign; the audio spans
	 * are added by the writer.
	 */
	synchronized TransmissionRecord snapshot(java.util.function.Function<UUID, TransmissionRecord.Talker> names) {
		List<TransmissionRecord.Talker> talkerList = new ArrayList<>();
		for(UUID talker : talkers)
			talkerList.add(names.apply(talker));
		List<TransmissionRecord.Hearer> hearerList = new ArrayList<>();
		for(HearerState state : hearers.values())
			hearerList.add(state.snapshot());
		hearerList.sort(java.util.Comparator.comparingLong(TransmissionRecord.Hearer::firstMs));
		String line = text.toString();
		if(kind == TransmissionKind.CW && !cw.isEmpty())
			line = CwDecoder.decode(cw);
		return new TransmissionRecord(id, kind, startMs, lastMs, startGameTime, dimension, List.copyOf(talkerList), radioKind, radioPos, position, frequency, band, mode,
				ctcss, List.copyOf(hearerList), linkedTo, line, List.copyOf(packets), List.copyOf(cw), List.of(), keepAudio ? frames : 0, tapeRecording, false);
	}
}
