package com.arrl.radiocraft.common.moderation;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin;
import com.arrl.radiocraft.common.radio.voice.ReceiverMix;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Plays a logged transmission to the admin who asked for it at the moderation computer, through a Simple Voice Chat
 * static channel to that player alone, so the client needs no Opus decoding of its own. The stored Opus frames go out
 * as they were, one channel per talker, each when its time comes (its epoch time less the record's start, against the
 * wall clock since playback began): about 2.5 frames a server tick, as the rest of the mod's audio. A player tape's
 * PCM, and "as heard" playback, are encoded on a background thread before playing starts; nothing here decodes or
 * encodes on the server thread.
 * <p>
 * "As heard" puts the voice through the receiver effects at the strength the chosen hearer logged: FM is the voice band
 * and the hiss and dropouts of a weak signal ({@link RadioVoiceProcessor#fmFade}); SSB is the voice band at the level an
 * AGC gives that strength over a quiet band. The band's own static, the noise floor and fading at the time were not
 * logged, so it is an approximation.
 * <p>
 * Playback stops on Stop, when the computer's screen closes, when the admin logs out, or at the end. Everything here
 * runs on the server thread except {@link #prepare}.
 */
@EventBusSubscriber(modid = Radiocraft.MOD_ID)
public final class ModerationPlayback {

	private ModerationPlayback() {}

	/** Frames sent this far ahead of their time, so the client's jitter buffer never runs dry. */
	private static final long LEAD_MS = 60L;
	/** Samples in one 20 ms frame at 48 kHz. */
	private static final int FRAME_SAMPLES = 960;
	/** The noise an SSB "as heard" playback assumes: a quiet band (the S3 static line). */
	private static final double QUIET_NOISE = 2.5D / 9.0D;
	/** Ticks between progress updates to the screen. */
	private static final int PROGRESS_TICKS = 5;

	/** One frame ready to go: when (ms after the start), on which talker's stream, and the Opus packet. */
	public record Out(long offsetMs, int stream, byte[] opus) {}

	private static final ExecutorService PREPARE = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "RadioCraft moderation playback");
		thread.setDaemon(true);
		return thread;
	});
	private static final AtomicLong TOKENS = new AtomicLong();
	/** The newest request per admin: an older one finishing late is dropped. */
	private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();
	private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

	private static final class Session {
		final UUID admin;
		final long id;
		final List<Out> frames;
		final long lengthMs;
		final Consumer<ComputerRequests.Reply> out;
		final Map<Integer, StaticAudioChannel> channels = new HashMap<>();
		final long startedMs = System.currentTimeMillis();
		int next = 0;
		int ticks = 0;

		Session(UUID admin, long id, List<Out> frames, Consumer<ComputerRequests.Reply> out) {
			this.admin = admin;
			this.id = id;
			this.frames = frames;
			this.lengthMs = frames.isEmpty() ? 0L : frames.getLast().offsetMs() + 20L;
			this.out = out;
		}
	}

	/** True while {@code admin} has a playback going or on its way. */
	public static boolean isPlaying(UUID admin) {
		return SESSIONS.containsKey(admin) || PENDING.containsKey(admin);
	}

	/**
	 * Starts playing transmission {@code id} to {@code admin}, stopping what they were playing. {@code hearer} is the
	 * index of the hearer to play it as heard by, or -1 to play it clean. Reading the audio writes "play" to the audit
	 * log ({@link ModerationLog#playback}). What happens is told through {@code out}: "playing" (with the length),
	 * "progress", "play_done", or "play_error" with a translation key.
	 */
	public static void start(ServerPlayer admin, ModerationLog log, long id, int hearer, Consumer<ComputerRequests.Reply> out) {
		stop(admin.getUUID());
		if(RadiocraftVoicePlugin.API == null || RadiocraftVoicePlugin.API.getConnectionOf(admin.getUUID()) == null) {
			out.accept(error("no_voice"));
			return;
		}
		long token = TOKENS.incrementAndGet();
		PENDING.put(admin.getUUID(), token);
		MinecraftServer server = admin.server;
		UUID who = admin.getUUID();
		RadioVoiceProcessor.Settings settings = RadioVoiceProcessor.currentSettings(); // Config read here, on the server thread.
		log.playback(id, who, admin.getGameProfile().getName())
				.thenApplyAsync(playback -> playback == null ? null : prepare(playback, hearer, settings), PREPARE)
				.whenComplete((frames, error) -> server.execute(() -> {
					if(!PENDING.remove(who, token))
						return; // Stopped, or another started, while it was being read.
					if(error != null) {
						Radiocraft.LOGGER.warn("Moderation playback of {} failed", id, error);
						out.accept(error("read_failed"));
					}
					else if(frames == null)
						out.accept(error("unknown"));
					else if(frames.isEmpty())
						out.accept(error("no_audio"));
					else {
						Session session = new Session(who, id, frames, out);
						SESSIONS.put(who, session);
						CompoundTag data = new CompoundTag();
						data.putLong("id", id);
						data.putLong("length", session.lengthMs);
						out.accept(new ComputerRequests.Reply("playing", data));
					}
				}));
	}

	/** Stops {@code admin}'s playback, if any; true if there was one. */
	public static boolean stop(UUID admin) {
		boolean pending = PENDING.remove(admin) != null;
		Session session = SESSIONS.remove(admin);
		if(session != null)
			session.channels.values().forEach(StaticAudioChannel::flush);
		return pending || session != null;
	}

	private static ComputerRequests.Reply error(String key) {
		CompoundTag data = new CompoundTag();
		data.putString("key", key);
		return new ComputerRequests.Reply("play_error", data);
	}

	// -------------------- PREPARING (background thread) --------------------

	/**
	 * The frames to send for {@code playback}, in time order: a tape's PCM encoded in 20 ms frames, or the stored Opus
	 * frames (put through the receiver effects first for an "as heard" {@code hearer}). Background thread.
	 */
	static List<Out> prepare(ModerationLog.Playback playback, int hearer, RadioVoiceProcessor.Settings settings) {
		List<Out> out = new ArrayList<>();
		if(playback.tapePcm().length > 0) {
			OpusEncoder encoder = RadiocraftVoicePlugin.API.createEncoder();
			try {
				short[] pcm = playback.tapePcm();
				for(int at = 0, frame = 0; at < pcm.length; at += FRAME_SAMPLES, frame++) {
					short[] chunk = new short[FRAME_SAMPLES];
					System.arraycopy(pcm, at, chunk, 0, Math.min(FRAME_SAMPLES, pcm.length - at));
					out.add(new Out(frame * 20L, 0, encoder.encode(chunk)));
				}
			}
			finally {
				encoder.close();
			}
			return out;
		}
		TransmissionRecord record = playback.record();
		TransmissionRecord.Hearer as = hearer >= 0 && hearer < record.hearers().size() ? record.hearers().get(hearer) : null;
		Map<Integer, Effects> effects = new HashMap<>();
		try {
			for(FrameFile.Frame frame : playback.frames()) {
				byte[] opus = frame.data();
				if(as != null)
					opus = effects.computeIfAbsent(frame.stream(), s -> new Effects()).apply(opus, record.mode(), as.strength(), settings);
				out.add(new Out(Math.max(0L, frame.epochMs() - record.startMs()), frame.stream(), opus));
			}
		}
		finally {
			effects.values().forEach(Effects::close);
		}
		out.sort(Comparator.comparingLong(Out::offsetMs));
		return out;
	}

	/** One talker's stream through a receiver: its decoder, the receiver's processing and an encoder. */
	private static final class Effects {
		private final OpusDecoder decoder = RadiocraftVoicePlugin.API.createDecoder();
		private final OpusEncoder encoder = RadiocraftVoicePlugin.API.createEncoder();
		private final RadioVoiceProcessor processor = new RadioVoiceProcessor();

		byte[] apply(byte[] opus, String mode, double strength, RadioVoiceProcessor.Settings settings) {
			short[] pcm = decoder.decode(opus);
			if("FM".equals(mode) || mode.isEmpty()) {
				processor.process(pcm, settings, 1.0D, 0.0D);
				processor.fmFade(pcm, strength, 1.0D);
			}
			else
				processor.process(pcm, RadioVoiceProcessor.Settings.SSB, ReceiverMix.voiceLevel(strength, QUIET_NOISE), 0.0D);
			return encoder.encode(pcm);
		}

		void close() {
			decoder.close();
			encoder.close();
		}
	}

	// -------------------- PLAYING (server thread) --------------------

	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		if(SESSIONS.isEmpty())
			return;
		MinecraftServer server = event.getServer();
		for(Session session : List.copyOf(SESSIONS.values()))
			tick(server, session);
	}

	private static void tick(MinecraftServer server, Session session) {
		ServerPlayer admin = server.getPlayerList().getPlayer(session.admin);
		if(admin == null || RadiocraftVoicePlugin.API == null) {
			SESSIONS.remove(session.admin, session);
			return;
		}
		long elapsed = System.currentTimeMillis() - session.startedMs;
		while(session.next < session.frames.size() && session.frames.get(session.next).offsetMs() <= elapsed + LEAD_MS) {
			Out frame = session.frames.get(session.next++);
			StaticAudioChannel channel = channel(admin, session, frame.stream());
			if(channel != null)
				channel.send(frame.opus());
		}
		if(++session.ticks % PROGRESS_TICKS == 0 || session.next >= session.frames.size()) {
			CompoundTag data = new CompoundTag();
			data.putLong("id", session.id);
			data.putLong("position", Math.min(elapsed, session.lengthMs));
			data.putLong("length", session.lengthMs);
			boolean done = session.next >= session.frames.size() && elapsed >= session.lengthMs;
			session.out.accept(new ComputerRequests.Reply(done ? "play_done" : "progress", data));
			if(done) {
				session.channels.values().forEach(StaticAudioChannel::flush);
				SESSIONS.remove(session.admin, session);
			}
		}
	}

	@Nullable
	private static StaticAudioChannel channel(ServerPlayer admin, Session session, int stream) {
		StaticAudioChannel channel = session.channels.get(stream);
		if(channel != null && !channel.isClosed())
			return channel;
		VoicechatConnection connection = RadiocraftVoicePlugin.API.getConnectionOf(admin.getUUID());
		if(connection == null)
			return null;
		channel = RadiocraftVoicePlugin.API.createStaticAudioChannel(UUID.randomUUID(), RadiocraftVoicePlugin.API.fromServerLevel(admin.serverLevel()), connection);
		if(channel != null)
			session.channels.put(stream, channel);
		return channel;
	}

	@SubscribeEvent
	public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		stop(event.getEntity().getUUID());
	}

	@SubscribeEvent
	public static void onServerStopping(ServerStoppingEvent event) {
		PENDING.clear();
		SESSIONS.clear();
	}
}
