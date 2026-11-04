package com.arrl.radiocraft.common.moderation;

import com.arrl.radiocraft.api.antenna.IAntennaPacket;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.morse.CWBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * Where the game tells the moderation log what goes out and what is heard. Every method returns at once when
 * recording is off (one volatile read), and when it is on costs a map lookup and, for kept audio, a byte copy and a
 * queue offer: no disk and no encoding on the server or voice chat thread.
 * <p>
 * Transmissions are told apart at their source by the antenna they leave from (and the kind of signal and its
 * frequency), which is also what every receiver sees as {@link IAntennaPacket#getSource()}: so a transmission is
 * logged once, and each receiver that hears it adds itself to it as a hearer.
 */
public final class ModerationHooks {

	private ModerationHooks() {}

	/** The log while radio recording is on, else null: the one read every hook makes first. */
	static volatile ModerationLog radio = null;
	/** The log while proximity recording is on, else null. */
	static volatile ModerationLog proximity = null;

	// -------------------- WHAT A TRANSMISSION IS --------------------

	/**
	 * What the next transmit on this thread is, when the transmitting code knows better than the log's guess (a repeater
	 * passing a signal on, a parrot's echo, DTMF tones, a repeater's own beeps). Set with {@link #push} around the
	 * transmit and put back with {@link #pop}.
	 *
	 * @param kind     the kind, or null to let the log decide (with {@code talker} set)
	 * @param skip     true to log nothing (a repeater's courtesy beep, hang time and ID)
	 * @param linkedTo the transmission repeated, for {@link TransmissionKind#REPEATED}
	 * @param talker   who is sending, when the transmit call does not say (CW keyed from a screen)
	 * @param owner    the radio the tones come from, for DTMF keys queued with {@link #dtmfQueued}
	 */
	public record Tag(@Nullable TransmissionKind kind, boolean skip, long linkedTo, @Nullable UUID talker, @Nullable Object owner) {

		public static final Tag SKIP = new Tag(null, true, 0L, null, null);
		public static final Tag PARROT = new Tag(TransmissionKind.PARROT, false, 0L, null, null);
		public static final Tag AUTOPATCH = new Tag(TransmissionKind.AUTOPATCH, false, 0L, null, null);

		/** A repeater passing on what it heard in {@code input}. */
		public static Tag repeated(IAntennaPacket input) {
			ModerationLog log = radio;
			return new Tag(TransmissionKind.REPEATED, false, log == null ? 0L : log.sourceId(input), null, null);
		}

		/** DTMF tones from {@code owner}. */
		public static Tag dtmf(Object owner) {
			return new Tag(TransmissionKind.DTMF, false, 0L, null, owner);
		}

		/** Whatever goes out, sent by {@code talker}. */
		public static Tag by(@Nullable UUID talker) {
			return new Tag(null, false, 0L, talker, null);
		}
	}

	private static final ThreadLocal<Tag> TAG = new ThreadLocal<>();

	/** True while radio recording is on: callers can skip building a {@link Tag} when it is off. */
	public static boolean recording() {
		return radio != null;
	}

	/** Sets the tag for transmits on this thread; returns the one it replaces, for {@link #pop}. */
	@Nullable
	public static Tag push(Tag tag) {
		Tag previous = TAG.get();
		TAG.set(tag);
		return previous;
	}

	/** Puts back the tag {@link #push} returned. */
	public static void pop(@Nullable Tag previous) {
		if(previous == null)
			TAG.remove();
		else
			TAG.set(previous);
	}

	@Nullable
	static Tag tag() {
		return TAG.get();
	}

	// -------------------- THE MICROPHONE PACKET --------------------

	/** The Opus packet the voice chat thread is handing out right now, and whose it is. */
	static final class Mic {
		@Nullable UUID speaker;
		byte[] opus;
	}

	private static final ThreadLocal<Mic> MIC = ThreadLocal.withInitial(Mic::new);

	/**
	 * A player's microphone packet is about to be handed to every radio that hears it, on this thread: radios that
	 * transmit it can keep the Opus packet as it arrived instead of encoding anything. Call {@link #micEnd} after.
	 */
	public static void micBegin(UUID speaker, byte[] opus) {
		if(radio == null)
			return;
		Mic mic = MIC.get();
		mic.speaker = speaker;
		mic.opus = opus;
	}

	public static void micEnd() {
		if(radio == null)
			return;
		Mic mic = MIC.get();
		mic.speaker = null;
		mic.opus = null;
	}

	/** The Opus packet of {@code speaker} being handed out on this thread, or null if this frame did not come from their microphone. */
	static byte[] micOpus(@Nullable UUID speaker) {
		Mic mic = MIC.get();
		return speaker != null && speaker.equals(mic.speaker) ? mic.opus : null;
	}

	/** Whose microphone packet is being handed out on this thread, or null. */
	@Nullable
	static UUID micSpeaker() {
		return MIC.get().speaker;
	}

	/** Plain voice chat from {@code player} at {@code x, y, z} (proximity recording). Voice chat thread. */
	public static void proximity(UUID player, Level level, double x, double y, double z, byte[] opus, boolean whispering) {
		if(opus.length > 0) // The computer listening to a player's voice chat live.
			com.arrl.radiocraft.common.radio.voice.RadioModeration.proximity(player, level, opus);
		ModerationLog log = proximity;
		if(log != null && opus.length > 0)
			log.proximityFrame(player, level, x, y, z, opus, whispering);
	}

	// -------------------- TRANSMIT --------------------

	/**
	 * A voice frame going out from {@code antenna} (what receivers see as the packet's source), sent by {@code radio}
	 * (a block entity, handheld, charger or manpack) on {@code hz}. {@code source} is the voice id the frame goes out
	 * under: the talker, or a block's own id. Call before the antenna transmits, so receivers find it open.
	 */
	public static void voice(@Nullable Object antenna, Object radio, @Nullable Level level, @Nullable UUID source, short[] pcm, float hz, @Nullable Band band, float ctcss) {
		ModerationLog log = ModerationHooks.radio;
		if(log != null && antenna != null && level != null)
			log.voiceFrame(antenna, radio, level, source, pcm, hz, band, ctcss);
	}

	/** A data packet going out from {@code antenna}, sent by {@code radio}, from {@code talker} if a player sent it. Server thread. */
	public static void data(@Nullable Object antenna, Object radio, @Nullable Level level, TncPacket packet, float hz, @Nullable Band band, @Nullable UUID talker) {
		ModerationLog log = ModerationHooks.radio;
		if(log != null && antenna != null && level != null)
			log.dataPacket(antenna, radio, level, packet, hz, band, talker);
	}

	/** CW key buffers going out from {@code antenna}, sent by {@code radio}, keyed by {@code talker} if known. Server thread. */
	public static void cw(@Nullable Object antenna, Object radio, @Nullable Level level, Collection<CWBuffer> buffers, float hz, @Nullable Band band, @Nullable UUID talker) {
		ModerationLog log = ModerationHooks.radio;
		if(log != null && antenna != null && level != null)
			log.cwBuffers(antenna, radio, level, buffers, hz, band, talker);
	}

	/** A commercial radio module's program on the air from {@code antenna} this tick. Note only, kept if someone heard it. Server thread. */
	public static void commercial(Object antenna, ServerLevel level, BlockPos module, String program, String callsign, float hz, @Nullable Band band) {
		ModerationLog log = ModerationHooks.radio;
		if(log != null)
			log.commercial(antenna, level, module, program, callsign, hz, band);
	}

	/** DTMF keys queued to go out from {@code owner} (a radio or handheld holder): written on the DTMF transmission. Server thread. */
	public static void dtmfQueued(Object owner, String keys) {
		ModerationLog log = ModerationHooks.radio;
		if(log != null)
			log.dtmfQueued(owner, keys);
	}

	/** A line of RTTY typed by {@code typist} on {@code hz}: names the typist on the open RTTY transmission. Server thread. */
	public static void rttyTypist(Level level, float hz, @Nullable UUID typist) {
		ModerationLog log = ModerationHooks.radio;
		if(log != null && typist != null)
			log.rttyTypist(level, hz, typist);
	}

	// -------------------- RECEIVE --------------------

	/**
	 * {@code receiver} heard {@code packet} at {@code strength} (after its own losses). {@code receiver} is the radio
	 * object (a block entity, handheld, charger or manpack); {@code listener} the player holding it, if any.
	 */
	public static void heard(IAntennaPacket packet, Object receiver, @Nullable Level level, @Nullable BlockPos pos, @Nullable Player listener, double strength) {
		com.arrl.radiocraft.common.radio.voice.RadioModeration.heard(packet, level, pos, listener); // The computer listening to what a player hears.
		ModerationLog log = ModerationHooks.radio;
		if(log != null && packet.getSource() != null)
			log.heard(packet, receiver, level, pos, listener, strength);
	}

	// -------------------- TAPES --------------------

	/**
	 * A tape player at {@code pos} started playing into radios under voice id {@code voiceId}: a player tape
	 * ({@code recording}) or a server tape ({@code track}).
	 */
	public static void tapePlaying(UUID voiceId, BlockPos pos, @Nullable UUID recording, String track) {
		ModerationLog log = ModerationHooks.radio;
		if(log != null)
			log.tapePlaying(voiceId, pos, recording, track);
	}

	/** {@code speakers} were recorded onto tape {@code recording} ({@code seconds} long) at {@code pos}. Server thread. */
	public static void tapeRecorded(ServerLevel level, BlockPos pos, UUID recording, int seconds, Collection<UUID> speakers) {
		ModerationLog log = ModerationLog.current();
		if(log != null && log.recordingRadio())
			log.tapeRecorded(level, pos, recording, seconds, speakers);
	}

	/**
	 * Tape {@code recording}'s file {@code file} is about to be recorded over. If a transmission in the retention window
	 * points at it, it is copied into the archive first. Call on the thread that writes the file, before it writes.
	 */
	public static void tapeOverwriting(UUID recording, Path file) {
		ModerationLog log = ModerationLog.current();
		if(log != null)
			log.tapeOverwriting(recording, file);
	}
}
