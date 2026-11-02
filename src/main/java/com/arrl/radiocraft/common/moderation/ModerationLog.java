package com.arrl.radiocraft.common.moderation;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.api.antenna.IAntenna;
import com.arrl.radiocraft.api.antenna.IAntennaPacket;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.Callsigns;
import com.arrl.radiocraft.common.radio.antenna.AntennaCWPacket;
import com.arrl.radiocraft.common.radio.antenna.AntennaVoicePacket;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.morse.CWBuffer;
import com.arrl.radiocraft.common.radio.tape.TapeRecordings;
import com.arrl.radiocraft.common.radio.voice.Afsk;
import com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin;
import com.arrl.radiocraft.common.radio.voice.handheld.DockedHandheld;
import com.arrl.radiocraft.common.radio.voice.handheld.ManpackRadio;
import com.arrl.radiocraft.common.radio.voice.handheld.PlayerRadio;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The radio moderation log (maintainer's design, 2026-11-04; see todo.md "Radio Moderation Computer"): every
 * transmission recorded once, at its source, with who heard it; player voice kept as the Opus packets that arrived,
 * data as data, notes for the rest. Off unless {@code moderation_record_radio} (and, separately,
 * {@code moderation_record_proximity}) is on in the server config.
 * <p>
 * The game reports through {@link ModerationHooks}. Open transmissions live here until a gap of {@link #GAP_MS} with
 * no frame, when the sweeper (server thread) closes them and hands them to the {@link ModerationStore}, whose one
 * thread does all the file work.
 * <p>
 * The moderation computer's screen uses the query API below, from the server thread: {@link #live}, {@link #query},
 * {@link #get}, {@link #playback}, {@link #flag}, {@link #unflag}, {@link #export}, {@link #links}, {@link #radios},
 * {@link #tapes}, {@link #audit} and {@link #auditLog}. Anything that touches disk returns a {@link CompletableFuture}
 * completed on the writer thread: hop back to the server thread ({@code server.execute}) before touching the world or
 * sending packets.
 */
@EventBusSubscriber(modid = Radiocraft.MOD_ID)
public final class ModerationLog {

	/** A transmission ends after this long with no frame. */
	public static final long GAP_MS = 1000L;
	/** A transmission longer than this is split, so a beacon that never stops still makes records. */
	public static final long MAX_MS = 10L * 60_000L;
	/** Frequencies this close on one antenna are one transmission (a poor crystal drifts under 1 kHz). */
	static final float TOLERANCE_HZ = 2500.0F;
	/** How far from a block radio's speaker a player counts as listening to it. */
	public static final double SPEAKER_RANGE = 16.0D;
	/** How often the sweeper looks for listeners near speakers, ms. */
	private static final long LISTENER_CHECK_MS = 1000L;
	private static final long HOUR_MS = 3_600_000L;
	private static final long DAY_MS = 86_400_000L;

	@Nullable private static volatile ModerationLog current;

	private final MinecraftServer server;
	private final ModerationStore store;
	private final Map<Object, OpenSet> open = new ConcurrentHashMap<>();
	private final AtomicLong lastId = new AtomicLong();
	/** Tape players playing, by the voice id they play under. */
	private final Map<UUID, TapeInfo> tapes = new ConcurrentHashMap<>();
	private final Map<Object, String> pendingDtmf = new ConcurrentHashMap<>();
	/** Players online: a voice id that is one is a talker (blocks send under ids of their own). */
	private final Set<UUID> online = ConcurrentHashMap.newKeySet();

	private volatile boolean recordRadio, recordProximity, metadataOnly, notice;
	/**
	 * Until when (epoch ms) a moderation computer's Live view is watching radio or plain voice chat: the hooks run
	 * then even with recording off, keeping what is on the air in memory only (nothing is written, see
	 * {@link #persists}). Nobody watching and recording off, the hooks stay off and cost nothing.
	 */
	private volatile long watchRadioUntilMs, watchProximityUntilMs;
	/** How long one Live request keeps the hooks running: the view asks twice a second. */
	private static final long WATCH_MS = 5000L;
	private volatile int retentionDays = 7;
	private volatile double capGb = 5.0D;
	private volatile RadiocraftServerConfig.ModerationQuality quality = RadiocraftServerConfig.ModerationQuality.ORIGINAL;
	private long lastRetentionMs = System.currentTimeMillis();
	private boolean warnedNarrowband = false;

	private record TapeInfo(@Nullable UUID recording, String track, BlockPos pos) {}

	/** What sent or heard something, worked out once when a transmission opens or a hearer first hears it. */
	private record Station(String kind, @Nullable BlockPos pos, @Nullable BlockPos antennaPos, String mode) {}

	private ModerationLog(MinecraftServer server) {
		this.server = server;
		this.store = new ModerationStore(server.getWorldPath(LevelResource.ROOT).resolve(Radiocraft.MOD_ID).resolve("moderation"),
				() -> RadiocraftVoicePlugin.API == null ? null : RadiocraftVoicePlugin.API.createEncoder());
	}

	/** The running server's log, or null when no server is running. */
	@Nullable
	public static ModerationLog current() {
		return current;
	}

	/** The log of {@code server}, or null. */
	@Nullable
	public static ModerationLog get(MinecraftServer server) {
		ModerationLog log = current;
		return log != null && log.server == server ? log : null;
	}

	/** The store, started (and its index loading) if it was not yet. */
	ModerationStore store() {
		if(!store.isRunning())
			store.start(retentionDays, capGb);
		return store;
	}

	public boolean recordingRadio() {
		return recordRadio;
	}

	public boolean recordingProximity() {
		return recordProximity;
	}

	/** The folder the log is kept in: the world's radiocraft/moderation. */
	public Path folder() {
		return store.root();
	}

	// -------------------- LIFECYCLE --------------------

	@SubscribeEvent
	public static void onServerStarted(ServerStartedEvent event) {
		ModerationLog log = new ModerationLog(event.getServer());
		current = log;
		log.refreshConfig();
		if(log.recordRadio || log.recordProximity || java.nio.file.Files.isDirectory(log.store.root()))
			log.store(); // Load what is there, and run retention on it.
	}

	@SubscribeEvent
	public static void onServerStopping(ServerStoppingEvent event) {
		ModerationLog log = current;
		if(log == null)
			return;
		ModerationHooks.radio = null;
		ModerationHooks.proximity = null;
		log.closeAll();
		log.store.stop();
		current = null;
	}

	@SubscribeEvent
	public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
		ModerationLog log = current;
		if(log == null || !(event.getEntity() instanceof ServerPlayer player))
			return;
		log.online.add(player.getUUID());
		if(log.notice && (log.recordRadio || log.recordProximity)) {
			String key = log.recordRadio && log.recordProximity ? "both" : log.recordRadio ? "radio" : "proximity";
			player.sendSystemMessage(Component.translatable("message.radiocraft.moderation.notice." + key, log.retentionDays).withStyle(ChatFormatting.GOLD));
		}
	}

	@SubscribeEvent
	public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		ModerationLog log = current;
		if(log != null)
			log.online.remove(event.getEntity().getUUID());
	}

	@SubscribeEvent
	public static void onServerTick(ServerTickEvent.Post event) {
		ModerationLog log = current;
		if(log != null && log.server == event.getServer())
			log.tick();
	}

	private void refreshConfig() {
		try {
			recordRadio = RadiocraftServerConfig.MODERATION_RECORD_RADIO.get();
			recordProximity = RadiocraftServerConfig.MODERATION_RECORD_PROXIMITY.get();
			metadataOnly = RadiocraftServerConfig.MODERATION_METADATA_ONLY.get();
			notice = RadiocraftServerConfig.MODERATION_NOTICE.get();
			retentionDays = RadiocraftServerConfig.MODERATION_RETENTION_DAYS.get();
			capGb = RadiocraftServerConfig.MODERATION_STORAGE_CAP_GB.get();
			quality = RadiocraftServerConfig.MODERATION_ARCHIVE_QUALITY.get();
		}
		catch(IllegalStateException e) {
			return; // The server config is not loaded yet.
		}
		if((recordRadio || recordProximity) && !store.isRunning())
			store();
		if(quality == RadiocraftServerConfig.ModerationQuality.NARROWBAND && !warnedNarrowband) {
			warnedNarrowband = true; // TODO narrowband: Simple Voice Chat's server API has no encoder bitrate setting.
			Radiocraft.LOGGER.warn("moderation_archive_quality = narrowband is not available yet: voice is kept as it arrived");
		}
		long now = System.currentTimeMillis();
		ModerationHooks.radio = (recordRadio && store.isRunning()) || now < watchRadioUntilMs ? this : null;
		ModerationHooks.proximity = (recordProximity && store.isRunning()) || now < watchProximityUntilMs ? this : null;
	}

	/**
	 * A Live view is open on radio ({@code proximity} false) or plain voice chat: keep the hooks running for a few
	 * seconds so it sees what is on the air, recording or not. Server thread.
	 */
	public void watchLive(boolean proximity) {
		long until = System.currentTimeMillis() + WATCH_MS;
		if(proximity)
			watchProximityUntilMs = until;
		else
			watchRadioUntilMs = until;
		refreshConfig();
	}

	/** True if transmissions of {@code kind} are recorded (kept on disk), not only watched live. */
	boolean persists(TransmissionKind kind) {
		return (kind == TransmissionKind.PROXIMITY ? recordProximity : recordRadio) && store.isRunning();
	}

	private void tick() {
		refreshConfig();
		if(server.getTickCount() % 5 != 0)
			return;
		long now = System.currentTimeMillis();
		for(Map.Entry<Object, OpenSet> entry : open.entrySet()) {
			OpenSet set = entry.getValue();
			for(OpenTransmission tx : set.snapshot()) {
				listeners(tx, now);
				if(now - tx.lastMs > GAP_MS || now - tx.startMs > MAX_MS)
					close(set, tx);
			}
			set.dropIfEmpty(entry.getKey(), open);
		}
		if(store.isRunning() && now - lastRetentionMs > HOUR_MS) {
			lastRetentionMs = now;
			store.purge(retentionDays, capGb);
		}
	}

	/** Closes every open transmission now (the server is stopping). */
	private void closeAll() {
		for(Map.Entry<Object, OpenSet> entry : open.entrySet()) {
			for(OpenTransmission tx : entry.getValue().snapshot())
				close(entry.getValue(), tx);
			entry.getValue().dropIfEmpty(entry.getKey(), open);
		}
	}

	private void close(OpenSet set, OpenTransmission tx) {
		if(!tx.close())
			return;
		set.remove(tx);
		TransmissionRecord record = tx.snapshot(this::talker);
		if(record.kind() == TransmissionKind.COMMERCIAL && record.hearers().isEmpty())
			return; // Programs nobody heard are not worth a line.
		if(persists(record.kind())) // Watched live only, with recording off: forgotten when it ends.
			store.close(record);
	}

	/** A talker's name and callsign. Server thread. */
	private TransmissionRecord.Talker talker(UUID uuid) {
		ServerPlayer player = server.getPlayerList().getPlayer(uuid);
		String name = player != null ? player.getGameProfile().getName()
				: server.getProfileCache() == null ? "" : server.getProfileCache().get(uuid).map(GameProfile::getName).orElse("");
		String callsign = Callsigns.playerCallsign(server, uuid);
		return new TransmissionRecord.Talker(uuid, name, callsign == null ? "" : callsign);
	}

	/**
	 * Fills in who was listening, on the server thread: names of hearers holding radios, players near the speakers of
	 * block radios that heard it, and for proximity, the players in voice chat range.
	 */
	private void listeners(OpenTransmission tx, long now) {
		if(tx.kind == TransmissionKind.PROXIMITY && tx.source instanceof UUID talker && now - tx.lastListenerCheck >= LISTENER_CHECK_MS) {
			tx.lastListenerCheck = now;
			ServerPlayer speaker = server.getPlayerList().getPlayer(talker);
			double range = RadiocraftVoicePlugin.API == null ? 48.0D : RadiocraftVoicePlugin.API.getBroadcastRange();
			if(speaker != null)
				for(ServerPlayer near : speaker.serverLevel().players()) {
					double distance = near.distanceTo(speaker);
					if(near != speaker && distance <= range)
						tx.hear(new ListenerKey(talker, near.getUUID()), near.getUUID(), "proximity", tx.dimension, near.blockPosition(), 1.0D - distance / range, now, null);
				}
		}
		for(Map.Entry<Object, OpenTransmission.HearerState> entry : List.copyOf(tx.hearers.entrySet())) {
			OpenTransmission.HearerState hearer = entry.getValue();
			UUID player = hearer.player;
			if(player != null && hearer.playerName.isEmpty())
				hearer.playerName = talker(player).name();
			if(player == null && hearer.level instanceof ServerLevel level && hearer.pos != null && now - hearer.nearbyCheckedMs >= LISTENER_CHECK_MS) {
				hearer.nearbyCheckedMs = now;
				for(ServerPlayer near : level.players())
					if(near.blockPosition().closerThan(hearer.pos, SPEAKER_RANGE))
						tx.hear(new ListenerKey(entry.getKey(), near.getUUID()), near.getUUID(), hearer.radioKind, hearer.dimension, hearer.pos, hearer.strength, now, null);
			}
		}
	}

	/** A player listening at a radio (or in range of a proximity talker), as a hearer of its own. */
	private record ListenerKey(Object at, UUID player) {}

	// -------------------- OPEN TRANSMISSIONS --------------------

	/** The transmissions open on one antenna (usually one). */
	private static final class OpenSet {
		private final List<OpenTransmission> list = new ArrayList<>(1);
		private boolean dead = false;

		/** The open one on {@code channel} near {@code hz} of {@code kind}. Under the lock. */
		@Nullable
		private OpenTransmission find(OpenTransmission.Channel channel, float hz, TransmissionKind kind) {
			for(OpenTransmission tx : list)
				if(tx.channel == channel && tx.kind == kind && Math.abs(tx.frequency - hz) <= TOLERANCE_HZ && !tx.isClosed())
					return tx;
			return null;
		}

		/** The open one a receiver hearing {@code channel} on {@code hz} from {@code voiceId} heard. */
		@Nullable
		synchronized OpenTransmission heard(OpenTransmission.Channel channel, float hz, @Nullable UUID voiceId) {
			OpenTransmission found = null;
			for(OpenTransmission tx : list) {
				if(tx.channel != channel || Math.abs(tx.frequency - hz) > TOLERANCE_HZ)
					continue;
				if(found == null || (voiceId != null && tx.voiceIds.contains(voiceId)))
					found = tx;
			}
			return found;
		}

		synchronized List<OpenTransmission> snapshot() {
			return List.copyOf(list);
		}

		synchronized void remove(OpenTransmission tx) {
			list.remove(tx);
		}

		synchronized void dropIfEmpty(Object key, Map<Object, OpenSet> map) {
			if(list.isEmpty()) {
				dead = true;
				map.remove(key, this);
			}
		}
	}

	/** Makes a transmission id: {@code ms * 1000} plus a counter, always rising. */
	private long nextId(long ms) {
		return lastId.updateAndGet(last -> Math.max(ms * 1000L, last + 1L));
	}

	/**
	 * The open transmission of {@code kind} on {@code source}'s {@code channel} near {@code hz}, opened if there is none
	 * ({@code opener} makes it).
	 */
	private OpenTransmission openOn(Object source, OpenTransmission.Channel channel, float hz, TransmissionKind kind, java.util.function.LongFunction<OpenTransmission> opener) {
		while(true) {
			OpenSet set = open.get(source);
			if(set == null)
				set = open.computeIfAbsent(source, k -> new OpenSet());
			synchronized(set) {
				if(set.dead)
					continue; // The sweeper dropped it just now: take the new one.
				OpenTransmission tx = set.find(channel, hz, kind);
				if(tx == null) {
					tx = opener.apply(nextId(System.currentTimeMillis()));
					set.list.add(tx);
				}
				return tx;
			}
		}
	}

	private OpenTransmission newTransmission(long id, TransmissionKind kind, Object source, OpenTransmission.Channel channel, float hz, Level level, Station station,
			@Nullable Band band, float ctcss, long linkedTo, boolean keepAudio, @Nullable UUID tapeRecording) {
		return new OpenTransmission(id, kind, source, channel, hz, TransmissionRecord.startMsOf(id), server.overworld().getGameTime(), level.dimension().location().toString(),
				station.kind(), station.pos(), station.antennaPos(), band == null ? "" : band.name(), station.mode(), ctcss, linkedTo, keepAudio, level, tapeRecording);
	}

	/** What {@code radio} (sending through {@code antenna}) is, for a record. */
	private static Station station(Object radio, @Nullable Object antenna, @Nullable Float hz) {
		BlockPos antennaPos = antenna instanceof IAntenna a && a.getAntennaPos() != null ? a.getAntennaPos().position().immutable() : null;
		if(radio instanceof BlockEntity be) {
			String kind = BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).getPath();
			String mode = radio instanceof RadioBlockEntity rbe ? rbe.isFM() ? "FM" : rbe.isLowerSideband() ? "LSB" : "USB" : "";
			return new Station(kind, be.getBlockPos().immutable(), antennaPos, mode);
		}
		if(radio instanceof PlayerRadio)
			return new Station("handheld", antennaPos, antennaPos, "FM");
		if(radio instanceof DockedHandheld)
			return new Station("desk_charger", antennaPos, antennaPos, "FM");
		if(radio instanceof ManpackRadio)
			return new Station("manpack", antennaPos, antennaPos, hz != null && hz < 10_000_000.0F ? "LSB" : "USB");
		return new Station(radio.getClass().getSimpleName().toLowerCase(Locale.ROOT), antennaPos, antennaPos, "");
	}

	/** The id of the open transmission {@code packet} belongs to, 0 if none. Any thread. */
	long sourceId(IAntennaPacket packet) {
		OpenSet set = packet.getSource() == null ? null : open.get(packet.getSource());
		OpenTransmission tx = set == null ? null : set.heard(channel(packet), packet.getFrequency(), packet instanceof AntennaVoicePacket voice ? voice.getSourcePlayer() : null);
		return tx == null ? 0L : tx.id;
	}

	private static OpenTransmission.Channel channel(IAntennaPacket packet) {
		if(packet instanceof AntennaVoicePacket)
			return OpenTransmission.Channel.VOICE;
		return packet instanceof AntennaCWPacket ? OpenTransmission.Channel.CW : OpenTransmission.Channel.DATA;
	}

	// -------------------- HOOKS (see ModerationHooks) --------------------

	void voiceFrame(Object antenna, Object radio, Level level, @Nullable UUID source, short[] pcm, float hz, @Nullable Band band, float ctcss) {
		ModerationHooks.Tag tag = ModerationHooks.tag();
		if(tag != null && tag.skip())
			return;
		TransmissionKind kind;
		UUID talker = source != null && online.contains(source) ? source : null;
		byte[] opus = null;
		TapeInfo tape = null;
		long linked = 0L;
		if(tag != null && tag.kind() != null) {
			kind = tag.kind();
			linked = tag.linkedTo();
			if(kind == TransmissionKind.AUTOPATCH) { // The phone caller's own Opus packet, when the call came from a player's microphone on this thread.
				UUID caller = ModerationHooks.micSpeaker();
				if(caller != null) {
					talker = caller;
					opus = ModerationHooks.micOpus(caller);
				}
			}
		}
		else if(Afsk.isDataSource(source))
			return; // A packet burst: the packet itself is logged as data.
		else if((opus = ModerationHooks.micOpus(source)) != null) {
			kind = TransmissionKind.PLAYER_VOICE;
			talker = source;
		}
		else if((tape = tapes.get(source)) != null)
			kind = tape.recording() != null ? TransmissionKind.TAPE : TransmissionKind.SERVER_TAPE;
		else
			kind = TransmissionKind.BLOCK_AUDIO;
		if(tag != null && tag.talker() != null)
			talker = tag.talker();

		boolean keep = kind.storesAudio() && !metadataOnly && persists(kind) && (opus != null || RadiocraftVoicePlugin.API != null);
		TransmissionKind txKind = kind;
		TapeInfo playing = tape;
		long now = System.currentTimeMillis();
		long link = linked;
		for(int attempt = 0; attempt < 2; attempt++) {
			OpenTransmission tx = openOn(antenna, OpenTransmission.Channel.VOICE, hz, txKind, id -> {
				OpenTransmission made = newTransmission(id, txKind, antenna, OpenTransmission.Channel.VOICE, hz, level, station(radio, antenna, hz), band, ctcss, link, keep,
						playing == null ? null : playing.recording());
				if(playing != null)
					made.appendText(playing.recording() == null ? playing.track() : "tape at " + playing.pos().toShortString());
				return made;
			});
			boolean ok = tx.keepAudio ? tx.audio(talker, now, store, opus, opus == null ? pcm : null) : tx.frame(talker, now) >= 0;
			if(ok) {
				if(source != null && !tx.voiceIds.contains(source))
					tx.voiceIds.add(source);
				if(tag != null && tag.owner() != null) {
					String keys = pendingDtmf.remove(tag.owner());
					if(keys != null)
						tx.appendText(keys);
				}
				return;
			}
		}
	}

	void proximityFrame(UUID player, Level level, double x, double y, double z, byte[] opus, boolean whispering) {
		boolean keep = !metadataOnly && persists(TransmissionKind.PROXIMITY);
		long now = System.currentTimeMillis();
		BlockPos at = BlockPos.containing(x, y, z);
		for(int attempt = 0; attempt < 2; attempt++) {
			OpenTransmission tx = openOn(player, OpenTransmission.Channel.VOICE, 0.0F, TransmissionKind.PROXIMITY, id -> newTransmission(id, TransmissionKind.PROXIMITY, player,
					OpenTransmission.Channel.VOICE, 0.0F, level, new Station("proximity", at, at, whispering ? "WHISPER" : ""), null, 0.0F, 0L, keep, null));
			if(tx.keepAudio ? tx.audio(player, now, store, opus, null) : tx.frame(player, now) >= 0)
				return;
		}
	}

	void dataPacket(Object antenna, Object radio, Level level, TncPacket packet, float hz, @Nullable Band band, @Nullable UUID talker) {
		long now = System.currentTimeMillis();
		TransmissionRecord.DataPacket record = new TransmissionRecord.DataPacket(now, packet.kind().name(), packet.save().toString(), summary(packet));
		String line = packet.kind() == TncPacket.Kind.RTTY ? packet.text() : summary(packet);
		boolean raw = packet.kind() == TncPacket.Kind.RTTY;
		for(int attempt = 0; attempt < 2; attempt++) {
			OpenTransmission tx = openOn(antenna, OpenTransmission.Channel.DATA, hz, TransmissionKind.DATA, id -> newTransmission(id, TransmissionKind.DATA, antenna,
					OpenTransmission.Channel.DATA, hz, level, withMode(station(radio, antenna, hz), "DATA"), band, 0.0F, 0L, false, null));
			if(tx.addPacket(record, line, raw, now, talker))
				return;
		}
	}

	void cwBuffers(Object antenna, Object radio, Level level, Collection<CWBuffer> buffers, float hz, @Nullable Band band, @Nullable UUID talker) {
		ModerationHooks.Tag tag = ModerationHooks.tag();
		UUID who = tag != null && tag.talker() != null ? tag.talker() : talker;
		List<TransmissionRecord.CwBuffer> list = new ArrayList<>(buffers.size());
		for(CWBuffer buffer : buffers)
			list.add(new TransmissionRecord.CwBuffer(buffer.getId(), buffer.toMask()));
		long now = System.currentTimeMillis();
		for(int attempt = 0; attempt < 2; attempt++) {
			OpenTransmission tx = openOn(antenna, OpenTransmission.Channel.CW, hz, TransmissionKind.CW, id -> newTransmission(id, TransmissionKind.CW, antenna,
					OpenTransmission.Channel.CW, hz, level, withMode(station(radio, antenna, hz), "CW"), band, 0.0F, 0L, false, null));
			if(tx.addCw(list, now, who))
				return;
		}
	}

	void commercial(Object antenna, ServerLevel level, BlockPos module, String program, String callsign, float hz, @Nullable Band band) {
		long now = System.currentTimeMillis();
		BlockPos antennaPos = antenna instanceof IAntenna a && a.getAntennaPos() != null ? a.getAntennaPos().position().immutable() : module;
		for(int attempt = 0; attempt < 2; attempt++) {
			OpenTransmission tx = openOn(antenna, OpenTransmission.Channel.VOICE, hz, TransmissionKind.COMMERCIAL, id -> {
				OpenTransmission made = newTransmission(id, TransmissionKind.COMMERCIAL, antenna, OpenTransmission.Channel.VOICE, hz, level,
						new Station("commercial_module", module.immutable(), antennaPos, ""), band, 0.0F, 0L, false, null);
				made.appendText(program + (callsign.isEmpty() ? "" : " " + callsign));
				return made;
			});
			if(tx.frame(null, now) >= 0)
				return;
		}
	}

	void dtmfQueued(Object owner, String keys) {
		pendingDtmf.merge(owner, keys, String::concat);
	}

	void rttyTypist(Level level, float hz, UUID typist) {
		String dimension = level.dimension().location().toString();
		long now = System.currentTimeMillis();
		for(OpenSet set : open.values())
			for(OpenTransmission tx : set.snapshot())
				if(tx.kind == TransmissionKind.DATA && tx.dimension.equals(dimension) && Math.abs(tx.frequency - hz) <= TOLERANCE_HZ && now - tx.lastMs < 5000L)
					tx.addTalker(typist);
	}

	void heard(IAntennaPacket packet, Object receiver, @Nullable Level level, @Nullable BlockPos pos, @Nullable Player listener, double strength) {
		OpenSet set = open.get(packet.getSource());
		if(set == null)
			return;
		OpenTransmission tx = set.heard(channel(packet), packet.getFrequency(), packet instanceof AntennaVoicePacket voice ? voice.getSourcePlayer() : null);
		if(tx == null)
			return;
		OpenTransmission.HearerState known = tx.hearers.get(receiver);
		if(known != null) {
			if(strength > known.strength)
				known.strength = (float)strength;
			return;
		}
		Station station = station(receiver, receiver instanceof IAntenna ? receiver : null, null);
		BlockPos at = pos != null ? pos.immutable() : station.pos();
		boolean holder = receiver instanceof PlayerRadio || receiver instanceof ManpackRadio;
		tx.hear(receiver, listener == null ? null : listener.getUUID(), station.kind(), level == null ? tx.dimension : level.dimension().location().toString(), at, strength,
				System.currentTimeMillis(), holder || listener != null ? null : level);
	}

	void tapePlaying(UUID voiceId, BlockPos pos, @Nullable UUID recording, String track) {
		tapes.put(voiceId, new TapeInfo(recording, track, pos.immutable()));
	}

	void tapeRecorded(ServerLevel level, BlockPos pos, UUID recording, int seconds, Collection<UUID> speakers) {
		if(!recordRadio)
			return; // Only watched live: nothing to keep.
		long end = System.currentTimeMillis();
		long start = end - seconds * 1000L;
		long id = nextId(start);
		List<TransmissionRecord.Talker> talkers = new ArrayList<>();
		for(UUID speaker : speakers)
			talkers.add(talker(speaker));
		TransmissionRecord record = new TransmissionRecord(id, TransmissionKind.TAPE_RECORDING, TransmissionRecord.startMsOf(id), end, level.getGameTime(),
				level.dimension().location().toString(), List.copyOf(talkers), "tape_player", pos.immutable(), pos.immutable(), 0.0F, "", "", 0.0F, List.of(), 0L,
				seconds + " s", List.of(), List.of(), List.of(), 0, recording, false);
		store().close(record);
	}

	void tapeOverwriting(UUID recording, Path file) {
		if(!store.isRunning())
			return;
		long cutoff = System.currentTimeMillis() - retentionDays * DAY_MS;
		boolean referenced = false;
		for(TransmissionRecord record : store.all())
			if(recording.equals(record.tapeRecording()) && (record.flagged() || record.startMs() >= cutoff)) {
				referenced = true;
				break;
			}
		if(!referenced)
			for(OpenSet set : open.values())
				for(OpenTransmission tx : set.snapshot())
					referenced |= recording.equals(tx.tapeRecording);
		if(referenced)
			store.archiveTape(recording, file);
	}

	private static Station withMode(Station station, String mode) {
		return new Station(station.kind(), station.pos(), station.antennaPos(), mode);
	}

	/** A readable line for a packet, as moderators read it in chat. */
	static String summary(TncPacket packet) {
		String text = switch(packet.kind()) {
			case RTTY -> packet.from() + ": " + packet.text();
			case MESSAGE -> packet.from() + ">" + (packet.to().isEmpty() ? "ALL" : packet.to()) + ": " + packet.messageBody();
			case BEACON -> packet.from() + (packet.position() == null ? "" : " at " + packet.position().getX() + ", " + packet.position().getZ())
					+ (packet.text().isEmpty() ? "" : ": " + packet.text());
			case FILE, FILE_HEADER -> packet.from() + ">" + packet.to() + ": " + packet.text();
			case FAX_CALL, FAX_ANSWER, FAX_PACKET, FAX_ACK -> packet.from() + ">" + packet.to() + ": " + packet.faxLabel();
			case SOLAR, ALERT -> packet.from() + ": " + String.join(" / ", packet.solarLines());
			case QUERY -> packet.from() + ": " + packet.text();
			case ACK -> packet.from() + ">" + packet.to() + " ack";
		};
		if(!packet.item().isEmpty())
			text += " [" + packet.item().getCount() + "x " + packet.item().getHoverName().getString() + "]";
		return packet.kind().name() + " " + text;
	}

	// ==================== QUERY API (for the moderation computer) ====================

	/**
	 * A transmission on the air now.
	 *
	 * @param id         its id (the record it becomes has the same)
	 * @param talkers    who is talking (names filled in on the server thread)
	 * @param hearers    how many radios and players heard it so far
	 */
	public record LiveTransmission(long id, TransmissionKind kind, List<TransmissionRecord.Talker> talkers, String radioKind, String dimension, @Nullable BlockPos radioPos,
			float frequency, String band, String mode, long startMs, long lastMs, int hearers) {}

	/** Everything on the air right now (closed ones drop out a second after their last frame), newest first. Server thread. */
	public List<LiveTransmission> live() {
		List<LiveTransmission> out = new ArrayList<>();
		for(OpenSet set : open.values())
			for(OpenTransmission tx : set.snapshot()) {
				if(tx.isClosed())
					continue;
				List<TransmissionRecord.Talker> talkers = new ArrayList<>();
				for(UUID uuid : tx.talkers())
					talkers.add(talker(uuid));
				out.add(new LiveTransmission(tx.id, tx.kind, List.copyOf(talkers), tx.radioKind, tx.dimension, tx.radioPos, tx.frequency, tx.band, tx.mode, tx.startMs,
						tx.lastMs, tx.hearers.size()));
			}
		out.sort(Comparator.comparingLong(LiveTransmission::startMs).reversed());
		return out;
	}

	/** The closed transmissions matching {@code query}, newest first, one page of them. Any thread. */
	public TransmissionQuery.Page query(TransmissionQuery query) {
		store();
		List<TransmissionRecord> items = new ArrayList<>();
		int total = 0;
		for(TransmissionRecord record : store.records.subMap(idAt(query.fromMs()), true, Math.max(idAt(query.fromMs()), idAt(query.toMs())), false).descendingMap().values()) {
			if(!query.matches(record))
				continue;
			if(total >= query.offset() && items.size() < query.limit())
				items.add(record);
			total++;
		}
		return new TransmissionQuery.Page(List.copyOf(items), total);
	}

	/** The lowest id a transmission starting at {@code ms} can have (clamped, so any ms works). */
	private static long idAt(long ms) {
		return ms <= 0L ? 0L : ms >= Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : ms * 1000L;
	}

	/** The closed transmissions that started from {@code fromMs} (inclusive) to {@code toMs} (exclusive), oldest first. */
	private Collection<TransmissionRecord> between(long fromMs, long toMs) {
		long from = idAt(fromMs);
		return store().records.subMap(from, true, Math.max(from, idAt(toMs)), false).values();
	}

	/** One closed transmission, or null if it is unknown (or past retention and not flagged). */
	@Nullable
	public TransmissionRecord get(long id) {
		return store().records.get(id);
	}

	/**
	 * What to play for a transmission: its Opus frames (decode each {@link FrameFile.Frame#stream} with a decoder of its
	 * own, and play them at their {@link FrameFile.Frame#epochMs} offsets from the record's start), or for a player
	 * tape, the tape's audio as 48 kHz PCM. To play what one hearer heard, put the audio through that receiver's
	 * effects at {@link TransmissionRecord.Hearer#strength}.
	 */
	public record Playback(TransmissionRecord record, List<FrameFile.Frame> frames, short[] tapePcm) {}

	/**
	 * Reads a transmission's audio back for playing, and writes "play" to the audit log for {@code by}. Completes with
	 * null for an unknown id.
	 */
	public CompletableFuture<Playback> playback(long id, @Nullable UUID by, String byName) {
		TransmissionRecord record = get(id);
		if(record == null)
			return CompletableFuture.completedFuture(null);
		audit(by, byName, "play", id, "");
		if(record.tapeRecording() != null) { // A tape played, or recorded: the tape's own audio.
			return store.readTape(record, liveTape(record.tapeRecording()))
					.thenApply(bytes -> new Playback(record, List.of(), TapeRecordings.decode(bytes)));
		}
		return store.readFrames(record).thenApply(frames -> new Playback(record, frames, new short[0]));
	}

	private Path liveTape(@Nullable UUID recording) {
		return recording == null ? store.root().resolve("none") : TapeRecordings.file(server, recording);
	}

	/** Flags a transmission (its audio copied to the kept folder, kept past retention); audit "flag". Completes with the record, null if unknown. */
	public CompletableFuture<TransmissionRecord> flag(long id, @Nullable UUID by, String byName) {
		TransmissionRecord record = get(id);
		audit(by, byName, "flag", id, "");
		return store.flag(id, liveTape(record == null ? null : record.tapeRecording()));
	}

	/** Unflags a transmission (its kept copy deleted); audit "unflag". Completes with the record as it is now, null if gone. */
	public CompletableFuture<TransmissionRecord> unflag(long id, @Nullable UUID by, String byName) {
		store();
		audit(by, byName, "unflag", id, "");
		return store.unflag(id, retentionDays);
	}

	/**
	 * Exports a transmission's audio to Ogg Opus files in the moderation folder's exports/ (one per talker); audit
	 * "export". Completes with the files written (empty if there was no audio), or null for an unknown id.
	 */
	public CompletableFuture<List<Path>> export(long id, @Nullable UUID by, String byName) {
		TransmissionRecord record = get(id);
		if(record == null)
			return CompletableFuture.completedFuture(null);
		TransmissionRecord.Talker talker = record.talker();
		audit(by, byName, "export", id, "");
		return store.export(record, liveTape(record.tapeRecording()), talker != null ? talker.name() : record.radioKind());
	}

	/** Exports every flagged transmission (see {@link #export}); completes with all the files written. */
	public CompletableFuture<List<Path>> exportFlagged(@Nullable UUID by, String byName) {
		List<CompletableFuture<List<Path>>> each = new ArrayList<>();
		for(TransmissionRecord record : store().all())
			if(record.flagged())
				each.add(export(record.id(), by, byName));
		return CompletableFuture.allOf(each.toArray(CompletableFuture[]::new)).thenApply(done -> {
			List<Path> files = new ArrayList<>();
			for(CompletableFuture<List<Path>> one : each) {
				List<Path> got = one.join();
				if(got != null)
					files.addAll(got);
			}
			return files;
		});
	}

	/**
	 * Who heard whom between {@code fromMs} and {@code toMs}, per day (server time zone): radio transmissions, or plain
	 * voice chat with {@code proximity}. See {@link LinkGraph}.
	 */
	public List<LinkGraph.Link> links(long fromMs, long toMs, boolean proximity) {
		List<TransmissionRecord> records = new ArrayList<>();
		for(TransmissionRecord record : between(fromMs, toMs))
			if((record.kind() == TransmissionKind.PROXIMITY) == proximity)
				records.add(record);
		return LinkGraph.count(records, ZoneId.systemDefault());
	}

	/**
	 * A radio seen in the log: where it is, what it is, how many transmissions it sent and heard in the window, and when
	 * it was last on the air. For the map.
	 */
	public record RadioSite(String dimension, BlockPos pos, String radioKind, int sent, int heard, long lastMs) {}

	/** Every radio that sent or heard something between {@code fromMs} and {@code toMs}, in {@code dimension} (null for all). */
	public List<RadioSite> radios(long fromMs, long toMs, @Nullable String dimension) {
		record Key(String dimension, BlockPos pos) {}
		Map<Key, int[]> counts = new LinkedHashMap<>();
		Map<Key, String> kinds = new HashMap<>();
		Map<Key, Long> last = new HashMap<>();
		for(TransmissionRecord record : between(fromMs, toMs)) {
			if(record.kind() == TransmissionKind.PROXIMITY || record.kind() == TransmissionKind.TAPE_RECORDING)
				continue;
			if(record.radioPos() != null && (dimension == null || dimension.equals(record.dimension()))) {
				Key key = new Key(record.dimension(), record.radioPos());
				counts.computeIfAbsent(key, k -> new int[2])[0]++;
				kinds.put(key, record.radioKind());
				last.merge(key, record.endMs(), Math::max);
			}
			for(TransmissionRecord.Hearer hearer : record.hearers()) {
				if(hearer.pos() == null || hearer.player() != null && !"handheld".equals(hearer.radioKind()) && !"manpack".equals(hearer.radioKind()))
					continue; // A listener near a radio is that radio.
				if(dimension != null && !dimension.equals(hearer.dimension()))
					continue;
				Key key = new Key(hearer.dimension(), hearer.pos());
				counts.computeIfAbsent(key, k -> new int[2])[1]++;
				kinds.putIfAbsent(key, hearer.radioKind());
			}
		}
		List<RadioSite> out = new ArrayList<>();
		for(Map.Entry<Key, int[]> entry : counts.entrySet())
			out.add(new RadioSite(entry.getKey().dimension(), entry.getKey().pos(), kinds.getOrDefault(entry.getKey(), ""), entry.getValue()[0], entry.getValue()[1],
					last.getOrDefault(entry.getKey(), 0L)));
		return out;
	}

	/**
	 * A player tape in the log: when and where it was recorded and who spoke on it (if that was logged), and how often
	 * it went over the air.
	 *
	 * @param recordId  the {@link TransmissionKind#TAPE_RECORDING} record (play it with {@link #playback}), 0 if the
	 *                  recording was made before the log saw it
	 * @param lastPlay  the last {@link TransmissionKind#TAPE} record that sent it, 0 if none
	 */
	public record TapeEntry(UUID recording, long recordedMs, int seconds, String dimension, @Nullable BlockPos pos, List<TransmissionRecord.Talker> speakers,
			int plays, long lastPlayedMs, long recordId, long lastPlay) {}

	/**
	 * Player tapes recorded or played between {@code fromMs} and {@code toMs}; with {@code speaker}, only tapes they
	 * spoke on. Newest first.
	 */
	public List<TapeEntry> tapes(@Nullable UUID speaker, long fromMs, long toMs) {
		Map<UUID, TransmissionRecord> recorded = new LinkedHashMap<>();
		Map<UUID, List<TransmissionRecord>> played = new HashMap<>();
		for(TransmissionRecord record : between(fromMs, toMs)) {
			if(record.tapeRecording() == null)
				continue;
			if(record.kind() == TransmissionKind.TAPE_RECORDING)
				recorded.put(record.tapeRecording(), record);
			else if(record.kind() == TransmissionKind.TAPE)
				played.computeIfAbsent(record.tapeRecording(), k -> new ArrayList<>()).add(record);
		}
		Set<UUID> ids = new java.util.LinkedHashSet<>(recorded.keySet());
		ids.addAll(played.keySet());
		List<TapeEntry> out = new ArrayList<>();
		for(UUID id : ids) {
			TransmissionRecord made = recorded.get(id);
			if(speaker != null && (made == null || !made.spokenBy(speaker)))
				continue;
			List<TransmissionRecord> plays = played.getOrDefault(id, List.of());
			TransmissionRecord lastPlay = plays.isEmpty() ? null : plays.getLast();
			out.add(new TapeEntry(id, made == null ? 0L : made.startMs(), made == null ? 0 : (int)(made.durationMs() / 1000L), made == null ? lastPlay.dimension() : made.dimension(),
					made == null ? lastPlay.radioPos() : made.radioPos(), made == null ? List.of() : made.talkers(), plays.size(), lastPlay == null ? 0L : lastPlay.startMs(),
					made == null ? 0L : made.id(), lastPlay == null ? 0L : lastPlay.id()));
		}
		out.sort(Comparator.comparingLong((TapeEntry entry) -> Math.max(entry.recordedMs(), entry.lastPlayedMs())).reversed());
		return out;
	}

	/**
	 * Writes a line to the audit log: {@code who} ({@code whoName}) did {@code action} ("play", "export", "flag",
	 * "unflag", "listen", "open"...) to transmission {@code target} (0 for none), with free {@code detail}.
	 */
	public void audit(@Nullable UUID who, String whoName, String action, long target, String detail) {
		store().audit(new ModerationStore.AuditEntry(System.currentTimeMillis(), who, whoName, action, target, detail));
	}

	/** Audit lines between {@code fromMs} and {@code toMs}, newest first, at most {@code limit}. */
	public List<ModerationStore.AuditEntry> auditLog(long fromMs, long toMs, int limit) {
		return store().auditEntries(fromMs, toMs, limit);
	}

	/**
	 * How the log is doing, for /radio moderation status and the computer's status bar.
	 *
	 * @param records      closed transmissions in memory (the window, and flagged ones)
	 * @param open         transmissions on the air now
	 * @param queuedFrames frames waiting for the writer
	 * @param dropped      frames dropped since start because the writer was behind
	 * @param storageBytes the moderation folder's size at the last retention run
	 */
	public record Status(boolean recordRadio, boolean recordProximity, boolean metadataOnly, int retentionDays, double capGb, boolean running, boolean loaded,
			int records, int open, int queuedFrames, long dropped, long storageBytes) {}

	public Status status() {
		int count = 0;
		for(OpenSet set : open.values())
			count += set.snapshot().size();
		return new Status(recordRadio, recordProximity, metadataOnly, retentionDays, capGb, store.isRunning(), store.isLoaded(), store.records.size(), count,
				store.queuedFrames(), store.droppedFrames(), store.storageBytes());
	}

	/** Runs retention and the storage cap now; completes with the number of files deleted. */
	public CompletableFuture<Integer> purge() {
		lastRetentionMs = System.currentTimeMillis();
		return store().purge(retentionDays, capGb);
	}

	/** Closes every open transmission now instead of after its gap (tests, and the purge command). Server thread. */
	public void closeOpenNow() {
		closeAll();
	}
}
