package com.arrl.radiocraft.common.radio.voice;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.antenna.IAntennaPacket;
import com.arrl.radiocraft.common.radio.Callsigns;
import com.arrl.radiocraft.common.radio.antenna.AntennaVoicePacket;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Radio moderation (maintainer, 2026-10-24): {@code /radio moderateall} lets an operator hear every voice that goes out
 * over any radio, clean (no static, no distance), with a line over the hotbar naming who is talking, and read every
 * RTTY and APRS packet sent, in chat. {@code /radio moderate <player>} does the same for one player only.
 * <p>
 * Voice is sent to the moderator on a static audio channel per speaker, encoded once per frame. A speaker heard by
 * several radios at once (a handheld and a desk mic, a repeater passing it on) is forwarded once per voice frame.
 * <p>
 * The moderation computer also listens to what one player hears ({@link #listenHears}): every voice a radio of theirs
 * receives (a handheld or manpack they carry, or a block radio within {@link #SPEAKER_RANGE} of them), clean, as the
 * transmitter sent it. That comes from {@link #heard}, which the receivers' moderation hook calls.
 */
@EventBusSubscriber(modid = Radiocraft.MOD_ID)
public final class RadioModeration {

	/** Moderator to the one player they listen to, or {@link #EVERYONE}. */
	private static final Map<UUID, UUID> MODERATORS = new ConcurrentHashMap<>();
	public static final UUID EVERYONE = new UUID(0L, 0L);
	/** Moderator to the player whose radios they hear through. */
	private static final Map<UUID, UUID> HEARERS = new ConcurrentHashMap<>();
	/** Moderator to the player whose plain voice chat they hear. */
	private static final Map<UUID, UUID> VOICE = new ConcurrentHashMap<>();
	/** Moderator to the frequency they are tuned to (hertz): everything sent there, whoever or whatever sends it. */
	private static final Map<UUID, Float> TUNED = new ConcurrentHashMap<>();
	/** How far from the frequency tuned to a transmission may be and still be heard (radios drift a little). */
	public static final float TUNED_WIDTH_HZ = 2_500.0F;
	/** How near a block radio's speaker a player must be for what it receives to count as what they hear. */
	public static final double SPEAKER_RANGE = 16.0D;
	/** Where each player being heard through stands, refreshed on the server thread each tick for the voice thread. */
	private static final Map<UUID, Standing> STANDING = new ConcurrentHashMap<>();
	/** Per player heard through and speaker: the last frame forwarded and the encoder, as for speakers above. */
	private static final Map<UUID, Long> LAST_HEARD = new ConcurrentHashMap<>();
	private static final Map<UUID, OpusEncoder> HEARD_ENCODERS = new ConcurrentHashMap<>();

	private record Standing(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, double x, double y, double z) {}

	/** Shortest gap between two frames forwarded for one speaker: a voice frame is 20 ms. */
	private static final long FRAME_NANOS = 15_000_000L;
	/** How often the "who is talking" line is refreshed, per speaker. */
	private static final long LABEL_NANOS = 1_000_000_000L;

	private static final Map<UUID, Long> LAST_FRAME = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_LABEL = new ConcurrentHashMap<>();
	private static final Map<UUID, OpusEncoder> ENCODERS = new ConcurrentHashMap<>();
	/** Per moderator, a channel per speaker. */
	private static final Map<UUID, Map<UUID, StaticAudioChannel>> CHANNELS = new ConcurrentHashMap<>();

	private RadioModeration() {}

	/** Starts {@code moderator} listening to {@code target}, or to everyone ({@link #EVERYONE}). */
	public static void listen(ServerPlayer moderator, UUID target) {
		HEARERS.remove(moderator.getUUID());
		VOICE.remove(moderator.getUUID());
		TUNED.remove(moderator.getUUID());
		MODERATORS.put(moderator.getUUID(), target);
	}

	/** Starts {@code moderator} hearing what {@code target}'s radios receive (see the class comment), instead of what anyone sends. */
	public static void listenHears(ServerPlayer moderator, UUID target) {
		MODERATORS.remove(moderator.getUUID());
		VOICE.remove(moderator.getUUID());
		TUNED.remove(moderator.getUUID());
		CHANNELS.remove(moderator.getUUID());
		HEARERS.put(moderator.getUUID(), target);
	}

	/**
	 * Starts {@code moderator} hearing {@code target}'s plain voice chat (no radio) wherever they are, as the Opus packets
	 * arrive (the moderation computer's Proximity tab). See {@link #proximity}.
	 */
	public static void listenVoice(ServerPlayer moderator, UUID target) {
		MODERATORS.remove(moderator.getUUID());
		HEARERS.remove(moderator.getUUID());
		TUNED.remove(moderator.getUUID());
		CHANNELS.remove(moderator.getUUID());
		VOICE.put(moderator.getUUID(), target);
	}

	/**
	 * Tunes {@code moderator} in to {@code frequencyHz}: they hear, clean, everything sent there (players, tapes,
	 * repeaters, commercial stations), whoever sends it (the moderation computer's Live tab, Listen).
	 */
	public static void listenFrequency(ServerPlayer moderator, float frequencyHz) {
		MODERATORS.remove(moderator.getUUID());
		HEARERS.remove(moderator.getUUID());
		VOICE.remove(moderator.getUUID());
		CHANNELS.remove(moderator.getUUID());
		TUNED.put(moderator.getUUID(), frequencyHz);
	}

	/** The frequency {@code moderator} is tuned to ({@link #listenFrequency}), or null. */
	@Nullable
	public static Float tunedTo(UUID moderator) {
		return TUNED.get(moderator);
	}

	private static boolean onFrequency(float tuned, float frequencyHz) {
		return Math.abs(tuned - frequencyHz) <= TUNED_WIDTH_HZ;
	}

	/** Stops; true if they were listening. */
	public static boolean stop(UUID moderator) {
		CHANNELS.remove(moderator);
		boolean hearing = HEARERS.remove(moderator) != null;
		boolean voice = VOICE.remove(moderator) != null;
		boolean tuned = TUNED.remove(moderator) != null;
		return MODERATORS.remove(moderator) != null || hearing || voice || tuned;
	}

	public static boolean isActive() {
		return !MODERATORS.isEmpty() || !HEARERS.isEmpty() || !VOICE.isEmpty() || !TUNED.isEmpty();
	}

	/** The player whose plain voice chat {@code moderator} hears ({@link #listenVoice}), or null. */
	@Nullable
	public static UUID hearingVoiceOf(UUID moderator) {
		return VOICE.get(moderator);
	}

	/**
	 * {@code speaker}'s microphone packet for plain voice chat, in {@code level}: forwarded as it is to moderators
	 * listening to their voice. Voice chat thread; returns at once while nobody does.
	 */
	public static void proximity(UUID speaker, Level level, byte[] opus) {
		if(VOICE.isEmpty() || RadiocraftVoicePlugin.API == null || !(level instanceof ServerLevel serverLevel))
			return;
		for(Map.Entry<UUID, UUID> entry : VOICE.entrySet()) {
			if(!entry.getValue().equals(speaker) || entry.getKey().equals(speaker))
				continue;
			StaticAudioChannel channel = channel(RadiocraftVoicePlugin.API.fromServerLevel(serverLevel), entry.getKey(), speaker);
			if(channel != null)
				channel.send(opus);
		}
	}

	/** Whose transmissions {@code moderator} listens to ({@link #EVERYONE} for all), or null. */
	@Nullable
	public static UUID listeningTo(UUID moderator) {
		return MODERATORS.get(moderator);
	}

	/** The player whose radios {@code moderator} hears through ({@link #listenHears}), or null. */
	@Nullable
	public static UUID hearingThrough(UUID moderator) {
		return HEARERS.get(moderator);
	}

	private static boolean hears(UUID target, @Nullable UUID speaker) {
		return EVERYONE.equals(target) || target.equals(speaker);
	}

	/**
	 * A voice frame going out over a radio ({@code frequencyHz}) from {@code speaker}. Called on the voice thread by
	 * every transmitter; does nothing while nobody moderates.
	 */
	public static void voice(de.maxhenkel.voicechat.api.ServerLevel level, @Nullable UUID speaker, short[] audio, float frequencyHz) {
		if(MODERATORS.isEmpty() && TUNED.isEmpty() || speaker == null || RadiocraftVoicePlugin.API == null)
			return;
		long now = System.nanoTime();
		Long last = LAST_FRAME.get(speaker);
		if(last != null && now - last < FRAME_NANOS)
			return; // Already forwarded through another radio.
		LAST_FRAME.put(speaker, now);
		byte[] opus = null;
		for(Map.Entry<UUID, UUID> entry : MODERATORS.entrySet()) {
			UUID moderator = entry.getKey();
			if(moderator.equals(speaker) || !hears(entry.getValue(), speaker))
				continue;
			StaticAudioChannel channel = channel(level, moderator, speaker);
			if(channel == null)
				continue;
			if(opus == null)
				opus = ENCODERS.computeIfAbsent(speaker, k -> RadiocraftVoicePlugin.API.createEncoder()).encode(audio);
			channel.send(opus);
		}
		for(Map.Entry<UUID, Float> entry : TUNED.entrySet()) {
			UUID moderator = entry.getKey();
			if(moderator.equals(speaker) || !onFrequency(entry.getValue(), frequencyHz))
				continue;
			StaticAudioChannel channel = channel(level, moderator, speaker);
			if(channel == null)
				continue;
			if(opus == null)
				opus = ENCODERS.computeIfAbsent(speaker, k -> RadiocraftVoicePlugin.API.createEncoder()).encode(audio);
			channel.send(opus);
		}
		Long labelled = LAST_LABEL.get(speaker);
		if(labelled == null || now - labelled > LABEL_NANOS) {
			LAST_LABEL.put(speaker, now);
			MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
			if(server != null)
				server.execute(() -> label(server, speaker, frequencyHz));
		}
	}

	@Nullable
	/**
	 * A commercial station's {@code frames} going out on {@code frequencyHz} under {@code voiceId} (it sends a few a
	 * tick, so they skip the once-a-frame check in {@link #voice}): to moderators tuned there. Server thread.
	 */
	public static void broadcast(ServerLevel level, UUID voiceId, List<short[]> frames, float frequencyHz) {
		if(TUNED.isEmpty() || RadiocraftVoicePlugin.API == null)
			return;
		de.maxhenkel.voicechat.api.ServerLevel voiceLevel = RadiocraftVoicePlugin.API.fromServerLevel(level);
		List<byte[]> encoded = null;
		for(Map.Entry<UUID, Float> entry : TUNED.entrySet()) {
			if(!onFrequency(entry.getValue(), frequencyHz))
				continue;
			StaticAudioChannel channel = channel(voiceLevel, entry.getKey(), voiceId);
			if(channel == null)
				continue;
			if(encoded == null) {
				OpusEncoder encoder = ENCODERS.computeIfAbsent(voiceId, k -> RadiocraftVoicePlugin.API.createEncoder());
				encoded = new java.util.ArrayList<>();
				for(short[] frame : frames)
					encoded.add(encoder.encode(frame));
			}
			encoded.forEach(channel::send);
		}
	}

	private static StaticAudioChannel channel(de.maxhenkel.voicechat.api.ServerLevel level, UUID moderator, UUID speaker) {
		Map<UUID, StaticAudioChannel> channels = CHANNELS.computeIfAbsent(moderator, k -> new ConcurrentHashMap<>());
		StaticAudioChannel channel = channels.get(speaker);
		if(channel != null)
			return channel;
		VoicechatConnection connection = RadiocraftVoicePlugin.API.getConnectionOf(moderator);
		if(connection == null)
			return null;
		UUID id = new UUID(moderator.getMostSignificantBits() ^ speaker.getLeastSignificantBits(), moderator.getLeastSignificantBits() ^ speaker.getMostSignificantBits());
		channel = RadiocraftVoicePlugin.API.createStaticAudioChannel(id, level, connection);
		if(channel != null)
			channels.put(speaker, channel);
		return channel;
	}

	/**
	 * A receiver heard {@code packet} at {@code pos} in {@code level}; {@code listener} is the player holding it (null for
	 * a block radio). Its voice goes to every moderator hearing through that holder, or through a player standing within
	 * {@link #SPEAKER_RANGE} of the radio, once per frame however many of their radios heard it. Called by the
	 * receivers' moderation hook on whichever thread received it, before the receiver changes the audio; returns at once
	 * while nobody hears through anyone.
	 */
	public static void heard(IAntennaPacket packet, @Nullable Level level, @Nullable BlockPos pos, @Nullable Player listener) {
		if(HEARERS.isEmpty() || !(packet instanceof AntennaVoicePacket voice) || voice.getSourcePlayer() == null || RadiocraftVoicePlugin.API == null)
			return;
		UUID speaker = voice.getSourcePlayer();
		byte[] opus = null;
		for(UUID target : java.util.Set.copyOf(HEARERS.values())) {
			if(!hearsThere(target, level, pos, listener))
				continue;
			UUID key = new UUID(target.getMostSignificantBits() ^ speaker.getLeastSignificantBits(), target.getLeastSignificantBits() ^ speaker.getMostSignificantBits());
			long now = System.nanoTime();
			Long last = LAST_HEARD.get(key);
			if(last != null && now - last < FRAME_NANOS)
				continue; // Another of their radios heard this frame.
			LAST_HEARD.put(key, now);
			for(Map.Entry<UUID, UUID> entry : HEARERS.entrySet()) {
				if(!entry.getValue().equals(target) || entry.getKey().equals(speaker))
					continue;
				StaticAudioChannel channel = channel(RadiocraftVoicePlugin.API.fromServerLevel(voice.getLevel()), entry.getKey(), speaker);
				if(channel == null)
					continue;
				if(opus == null)
					opus = HEARD_ENCODERS.computeIfAbsent(key, k -> RadiocraftVoicePlugin.API.createEncoder()).encode(voice.getRawAudio().clone());
				channel.send(opus);
			}
			Long labelled = LAST_LABEL.get(key);
			if(labelled == null || now - labelled > LABEL_NANOS) {
				LAST_LABEL.put(key, now);
				MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
				if(server != null)
					server.execute(() -> heardLabel(server, target, speaker, packet.getFrequency()));
			}
		}
	}

	/** True if {@code target} hears what a radio at {@code pos} receives: they hold it, or stand near its speaker. */
	private static boolean hearsThere(UUID target, @Nullable Level level, @Nullable BlockPos pos, @Nullable Player listener) {
		if(listener != null)
			return listener.getUUID().equals(target);
		Standing standing = STANDING.get(target);
		return standing != null && level != null && pos != null && level.dimension().equals(standing.dimension())
				&& pos.getCenter().distanceToSqr(standing.x(), standing.y(), standing.z()) <= SPEAKER_RANGE * SPEAKER_RANGE;
	}

	/** "Heard by Name: Speaker, 146.520 MHz" over the hotbars of those hearing through {@code target}. Server thread. */
	private static void heardLabel(MinecraftServer server, UUID target, UUID speaker, float frequencyHz) {
		ServerPlayer through = server.getPlayerList().getPlayer(target);
		ServerPlayer talking = server.getPlayerList().getPlayer(speaker);
		String callsign = Callsigns.playerCallsign(server, speaker);
		String name = (talking == null ? speaker.toString().substring(0, 8) : talking.getGameProfile().getName()) + (callsign == null ? "" : " (" + callsign + ")");
		Component line = Component.translatable("message.radiocraft.moderate.heard", through == null ? target.toString().substring(0, 8) : through.getGameProfile().getName(),
				name, String.format(Locale.ROOT, "%.3f", frequencyHz / 1_000_000.0D)).withStyle(ChatFormatting.AQUA);
		for(Map.Entry<UUID, UUID> entry : HEARERS.entrySet()) {
			ServerPlayer moderator = server.getPlayerList().getPlayer(entry.getKey());
			if(moderator != null && entry.getValue().equals(target))
				moderator.displayClientMessage(line, true);
		}
	}

	/** Keeps where each player heard through stands, for the voice thread's {@link #hearsThere}. */
	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		if(HEARERS.isEmpty()) {
			if(!STANDING.isEmpty())
				STANDING.clear();
			return;
		}
		STANDING.keySet().retainAll(java.util.Set.copyOf(HEARERS.values()));
		for(UUID target : HEARERS.values()) {
			ServerPlayer player = event.getServer().getPlayerList().getPlayer(target);
			if(player == null)
				STANDING.remove(target);
			else
				STANDING.put(target, new Standing(player.level().dimension(), player.getX(), player.getEyeY(), player.getZ()));
		}
	}

	/** "On air: Name (K1ABC) 146.520 MHz" over the moderators' hotbars. Server thread. */
	private static void label(MinecraftServer server, UUID speaker, float frequencyHz) {
		ServerPlayer player = server.getPlayerList().getPlayer(speaker);
		String name = player == null ? speaker.toString().substring(0, 8) : player.getGameProfile().getName();
		String callsign = Callsigns.playerCallsign(server, speaker);
		Component line = Component.translatable("message.radiocraft.moderate.on_air", name + (callsign == null ? "" : " (" + callsign + ")"),
				String.format(Locale.ROOT, "%.3f", frequencyHz / 1_000_000.0D)).withStyle(ChatFormatting.AQUA);
		for(Map.Entry<UUID, UUID> entry : MODERATORS.entrySet()) {
			ServerPlayer moderator = server.getPlayerList().getPlayer(entry.getKey());
			if(moderator != null && !entry.getKey().equals(speaker) && hears(entry.getValue(), speaker))
				moderator.displayClientMessage(line, true);
		}
		for(Map.Entry<UUID, Float> entry : TUNED.entrySet()) {
			ServerPlayer moderator = server.getPlayerList().getPlayer(entry.getKey());
			if(moderator != null && !entry.getKey().equals(speaker) && onFrequency(entry.getValue(), frequencyHz))
				moderator.displayClientMessage(line, true);
		}
	}

	/** An RTTY or APRS packet going out on {@code frequencyHz}: shown in the moderators' chat. Server thread. */
	public static void data(ServerLevel level, TncPacket packet, float frequencyHz) {
		if(MODERATORS.isEmpty())
			return;
		MinecraftServer server = level.getServer();
		String mhz = String.format(Locale.ROOT, "%.3f", frequencyHz / 1_000_000.0D);
		String text = switch(packet.kind()) {
			case RTTY -> null; // A character at a time: the teletype gives moderators whole lines (see rttyLine).
			case MESSAGE -> "[APRS " + mhz + "] " + packet.from() + ">" + (packet.to().isEmpty() ? "ALL" : packet.to()) + ": " + packet.messageBody();
			case BEACON -> "[APRS " + mhz + "] " + packet.from() + (packet.position() == null ? "" : " at " + packet.position().getX() + ", " + packet.position().getZ())
					+ (packet.text().isEmpty() ? "" : ": " + packet.text());
			case FILE -> "[FILE " + mhz + "] " + packet.from() + ">" + packet.to() + ": " + packet.text();
			case FAX_CALL -> "[FAX " + mhz + "] " + packet.from() + ">" + packet.to() + ": " + packet.faxLabel(); // The call says what is coming; the packets are just numbers.
			case SOLAR -> "[SOLAR " + mhz + "] " + packet.from() + ": " + String.join(" / ", packet.solarLines());
			case ALERT -> "[SOLAR ALERT " + mhz + "] " + packet.from() + ": " + String.join(" / ", packet.solarLines());
			case QUERY -> "[QUERY " + mhz + "] " + packet.from() + ": " + packet.text();
			case FILE_HEADER, ACK, FAX_ANSWER, FAX_PACKET, FAX_ACK -> null;
		};
		if(text == null)
			return;
		Component line = Component.literal(text).withStyle(ChatFormatting.GRAY);
		for(Map.Entry<UUID, UUID> entry : MODERATORS.entrySet()) {
			ServerPlayer moderator = server.getPlayerList().getPlayer(entry.getKey());
			if(moderator == null)
				continue;
			UUID target = entry.getValue();
			if(!EVERYONE.equals(target)) { // One player: their station's packets, by callsign.
				String callsign = Callsigns.playerCallsign(server, target);
				if(callsign == null || !callsign.equalsIgnoreCase(packet.from()))
					continue;
			}
			moderator.sendSystemMessage(line);
		}
	}

	/**
	 * A line of RTTY a player typed on a teletype on {@code frequencyHz}: shown to moderators listening to everyone or
	 * to {@code typist}, with the player's name and the callsign it went out under.
	 */
	public static void rttyLine(ServerLevel level, float frequencyHz, @Nullable UUID typist, String typistName, String callsign, String line) {
		if(MODERATORS.isEmpty())
			return;
		String mhz = String.format(Locale.ROOT, "%.3f", frequencyHz / 1_000_000.0D);
		String who = typistName.isEmpty() ? callsign : typistName + (callsign.isEmpty() ? "" : " (" + callsign + ")");
		Component text = Component.literal("[RTTY " + mhz + "] " + who + ": " + line).withStyle(ChatFormatting.GRAY);
		MinecraftServer server = level.getServer();
		for(Map.Entry<UUID, UUID> entry : MODERATORS.entrySet()) {
			ServerPlayer moderator = server.getPlayerList().getPlayer(entry.getKey());
			if(moderator != null && hears(entry.getValue(), typist))
				moderator.sendSystemMessage(text);
		}
	}

	@SubscribeEvent
	public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
		UUID uuid = event.getEntity().getUUID();
		stop(uuid);
		ENCODERS.computeIfPresent(uuid, (k, encoder) -> {
			encoder.close();
			return null;
		});
		LAST_FRAME.remove(uuid);
		LAST_LABEL.remove(uuid);
		HEARERS.values().removeIf(uuid::equals); // Nobody hears through a player who left.
		VOICE.remove(uuid);
		STANDING.remove(uuid);
	}

	@SubscribeEvent
	public static void onServerStopped(ServerStoppedEvent event) {
		MODERATORS.clear();
		HEARERS.clear();
		VOICE.clear();
		TUNED.clear();
		STANDING.clear();
		CHANNELS.clear();
		ENCODERS.values().forEach(OpusEncoder::close);
		ENCODERS.clear();
		HEARD_ENCODERS.values().forEach(OpusEncoder::close);
		HEARD_ENCODERS.clear();
		LAST_FRAME.clear();
		LAST_HEARD.clear();
		LAST_LABEL.clear();
	}
}
