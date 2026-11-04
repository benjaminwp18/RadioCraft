package com.arrl.radiocraft.common.moderation;

import com.arrl.radiocraft.common.blockentities.ModerationComputerBlockEntity;
import com.arrl.radiocraft.common.radio.voice.RadioModeration;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The server side of the moderation computer's screen: carries out one request ({@code action} with its {@code data},
 * from an {@link com.arrl.radiocraft.common.network.serverbound.SStationPacket}) for an admin at a computer, and answers
 * through {@code out} with {@link Reply}s (sent to the client as
 * {@link com.arrl.radiocraft.common.network.clientbound.CModerationPacket}s). The caller has already checked the lock
 * ({@link com.arrl.radiocraft.common.menus.ModerationComputerMenu#handleAction}); owner-only requests check ownership
 * here. Answers are paged and capped well under the packet size limit. Anything the log does on its writer thread is
 * answered back on the server thread.
 * <p>
 * Requests: open, status, access, add_admin, remove_admin, transfer (owner only), live, listen, stop_listen, query,
 * detail, play, stop_play, flag, unflag, export, export_flagged, links, radios, tapes, audit. Answers: status, access,
 * live, listening, page, detail, playing, progress, play_done, play_error, play_stopped, flagged, exported, links,
 * radios, tapes, audit, message and error (both with a translation key in "key" and an argument in "arg").
 */
public final class ComputerRequests {

	private ComputerRequests() {}

	/** One answer: its kind and data. */
	public record Reply(String kind, CompoundTag data) {}

	/** Most rows of a page of transmissions, and the default. */
	public static final int MAX_PAGE = 30;
	static final int MAX_LIVE = 40;
	static final int MAX_HEARERS = 48;
	static final int MAX_PACKETS = 48;
	static final int MAX_LINKS = 150;
	static final int MAX_SITES = 300;
	static final int MAX_TAPES = 100;
	static final int MAX_AUDIT = 100;
	/** Records looked through for each radio's last talker and frequency on the map. */
	private static final int SITE_SCAN = 5000;

	/** Carries out {@code action} for {@code player} at {@code computer}. Server thread. */
	public static void handle(ModerationComputerBlockEntity computer, ServerPlayer player, String action, CompoundTag data, Consumer<Reply> out) {
		MinecraftServer server = player.server;
		ModerationLog log = ModerationLog.get(server);
		switch(action) {
			case "open" -> {
				audit(log, player, "open", 0L, computer.getBlockPos().toShortString());
				out.accept(status(log, player));
				out.accept(access(computer, player));
				out.accept(listening(player));
			}
			case "status" -> out.accept(status(log, player));
			case "access" -> out.accept(access(computer, player));
			case "add_admin", "transfer" -> ownerAction(computer, player, action, data, log, out);
			case "remove_admin" -> {
				if(!ownerOnly(computer, player, out))
					return;
				UUID uuid = uuid(data.getString("uuid"));
				String name = uuid == null ? "" : computer.getAdmins().getOrDefault(uuid, "");
				if(uuid != null && computer.removeAdmin(uuid)) {
					audit(log, player, "admin_remove", 0L, name);
					out.accept(message("admin_removed", name));
				}
				out.accept(access(computer, player));
			}
			case "live" -> {
				if(log != null)
					log.watchLive(data.getBoolean("prox")); // Live works with recording off: it keeps the hooks running while watched.
				out.accept(live(log, player, data.getBoolean("prox"), data.getBoolean("system")));
			}
			case "listen" -> listen(log, player, data, out);
			case "stop_listen" -> {
				RadioModeration.stop(player.getUUID());
				out.accept(listening(player));
			}
			case "query" -> {
				if(!running(log)) {
					out.accept(emptyPage(data.getBoolean("prox")));
					return;
				}
				String name = data.getString("player").trim();
				if(name.isEmpty()) {
					out.accept(page(log, data, null));
					return;
				}
				resolve(server, name, profile -> {
					if(profile == null)
						out.accept(error("unknown_player", name));
					else
						out.accept(page(log, data, profile.getId()));
				});
			}
			case "detail" -> {
				TransmissionRecord record = running(log) ? log.get(data.getLong("id")) : null;
				out.accept(record == null ? error("unknown_transmission", Long.toString(data.getLong("id"))) : new Reply("detail", detail(record)));
			}
			case "play" -> {
				if(!running(log)) {
					out.accept(error("not_running", ""));
					return;
				}
				ModerationPlayback.start(player, log, data.getLong("id"), data.contains("hearer") ? data.getInt("hearer") : -1, out);
			}
			case "stop_play" -> {
				ModerationPlayback.stop(player.getUUID());
				out.accept(new Reply("play_stopped", new CompoundTag()));
			}
			case "flag", "unflag" -> {
				if(!running(log))
					return;
				long id = data.getLong("id");
				boolean flag = action.equals("flag");
				CompletableFuture<TransmissionRecord> done = flag ? log.flag(id, player.getUUID(), name(player)) : log.unflag(id, player.getUUID(), name(player));
				later(server, done, out, record -> {
					CompoundTag tag = new CompoundTag();
					tag.putLong("id", id);
					tag.putBoolean("flagged", record != null && record.flagged());
					return new Reply("flagged", tag);
				});
			}
			case "export", "export_flagged" -> {
				if(!running(log))
					return;
				CompletableFuture<List<Path>> done = action.equals("export") ? log.export(data.getLong("id"), player.getUUID(), name(player))
						: log.exportFlagged(player.getUUID(), name(player));
				later(server, done, out, files -> exported(server, log, files));
			}
			case "links" -> out.accept(links(log, data));
			case "radios" -> out.accept(radios(log, server, data));
			case "tapes" -> {
				String name = data.getString("player").trim();
				if(name.isEmpty()) {
					out.accept(tapes(log, data, null));
					return;
				}
				resolve(server, name, profile -> out.accept(profile == null ? error("unknown_player", name) : tapes(log, data, profile.getId())));
			}
			case "audit" -> out.accept(auditLog(log, data));
			default -> {}
		}
	}

	// -------------------- STATUS AND ACCESS --------------------

	private static boolean running(@Nullable ModerationLog log) {
		return log != null && log.status().running();
	}

	private static String name(ServerPlayer player) {
		return player.getGameProfile().getName();
	}

	/** Writes to the audit log while it is running (a computer opened with recording off and no log leaves none). */
	private static void audit(@Nullable ModerationLog log, ServerPlayer player, String action, long target, String detail) {
		if(running(log))
			log.audit(player.getUUID(), name(player), action, target, detail);
	}

	/** Recording on or off, storage used against the cap, and who is online (for the screen's player lists). */
	static Reply status(@Nullable ModerationLog log, ServerPlayer player) {
		CompoundTag tag = new CompoundTag();
		if(log != null) {
			ModerationLog.Status status = log.status();
			tag.putBoolean("radio", status.recordRadio());
			tag.putBoolean("proximity", status.recordProximity());
			tag.putBoolean("metadata", status.metadataOnly());
			tag.putInt("days", status.retentionDays());
			tag.putDouble("cap", status.capGb());
			tag.putBoolean("running", status.running());
			tag.putInt("records", status.records());
			tag.putInt("open", status.open());
			tag.putLong("dropped", status.dropped());
			tag.putLong("bytes", status.storageBytes());
		}
		ListTag players = new ListTag();
		for(ServerPlayer online : player.server.getPlayerList().getPlayers())
			players.add(StringTag.valueOf(online.getGameProfile().getName()));
		tag.put("players", players);
		ListTag dims = new ListTag();
		for(ResourceKey<Level> key : player.server.levelKeys())
			dims.add(StringTag.valueOf(key.location().toString()));
		tag.put("dims", dims);
		return new Reply("status", tag);
	}

	/** The owner, the admins, and whether the asker is the owner (only they may change it). */
	static Reply access(ModerationComputerBlockEntity computer, ServerPlayer player) {
		CompoundTag tag = new CompoundTag();
		tag.putString("owner", computer.getOwnerName());
		if(computer.getOwner() != null)
			tag.putString("ownerUuid", computer.getOwner().toString());
		tag.putBoolean("isOwner", computer.isOwner(player));
		ListTag admins = new ListTag();
		for(Map.Entry<UUID, String> admin : computer.getAdmins().entrySet()) {
			CompoundTag entry = new CompoundTag();
			entry.putString("uuid", admin.getKey().toString());
			entry.putString("name", admin.getValue());
			admins.add(entry);
		}
		tag.put("admins", admins);
		return new Reply("access", tag);
	}

	private static boolean ownerOnly(ModerationComputerBlockEntity computer, ServerPlayer player, Consumer<Reply> out) {
		if(computer.isOwner(player))
			return true;
		out.accept(error("owner_only", ""));
		return false;
	}

	/** Adding an admin or handing the computer over, by a player's name (online, or known to the server). */
	private static void ownerAction(ModerationComputerBlockEntity computer, ServerPlayer player, String action, CompoundTag data, @Nullable ModerationLog log, Consumer<Reply> out) {
		if(!ownerOnly(computer, player, out))
			return;
		String name = data.getString("name").trim();
		if(name.isEmpty())
			return;
		resolve(player.server, name, profile -> {
			if(computer.isRemoved() || !computer.isOwner(player))
				return; // Changed while the name was being looked up.
			if(profile == null)
				out.accept(error("unknown_player", name));
			else if(action.equals("transfer")) {
				if(!profile.getId().equals(player.getUUID())) {
					computer.transferTo(profile.getId(), profile.getName());
					audit(log, player, "transfer", 0L, profile.getName());
					out.accept(message("transferred", profile.getName()));
				}
			}
			else if(computer.addAdmin(profile.getId(), profile.getName())) {
				audit(log, player, "admin_add", 0L, profile.getName());
				out.accept(message("admin_added", profile.getName()));
			}
			else
				out.accept(error(computer.getAdmins().size() >= ModerationComputerBlockEntity.MAX_ADMINS ? "admins_full" : "already_admin", profile.getName()));
			out.accept(access(computer, player));
		});
	}

	/**
	 * Finds the player named {@code name}: online, or in the server's profile cache (looked up off the server thread,
	 * since an unknown name may go out to the account servers). {@code then} gets the profile, or null, on the server
	 * thread.
	 */
	static void resolve(MinecraftServer server, String name, Consumer<GameProfile> then) {
		ServerPlayer online = server.getPlayerList().getPlayerByName(name);
		if(online != null || server.getProfileCache() == null) {
			then.accept(online == null ? null : online.getGameProfile());
			return;
		}
		server.getProfileCache().getAsync(name).whenComplete((found, error) -> server.execute(() -> then.accept(error != null || found == null ? null : found.orElse(null))));
	}

	@Nullable
	private static UUID uuid(String text) {
		try {
			return text.isEmpty() ? null : UUID.fromString(text);
		}
		catch(IllegalArgumentException e) {
			return null;
		}
	}

	// -------------------- LIVE --------------------

	/**
	 * What is on the air now (radio, or with {@code proximity} plain voice chat), newest first, and who the asker listens
	 * to. The world's own stations (commercial modules, server tapes, repeats, echoes) only with {@code system}.
	 */
	static Reply live(@Nullable ModerationLog log, ServerPlayer player, boolean proximity, boolean system) {
		CompoundTag tag = new CompoundTag();
		ListTag rows = new ListTag();
		if(log != null)
			for(ModerationLog.LiveTransmission tx : log.live()) {
				if((tx.kind() == TransmissionKind.PROXIMITY) != proximity || (!system && tx.kind().isSystem()))
					continue;
				if(rows.size() >= MAX_LIVE)
					break;
				CompoundTag row = new CompoundTag();
				row.putLong("id", tx.id());
				row.putString("kind", tx.kind().id());
				row.put("talkers", talkers(tx.talkers()));
				row.putString("radio", tx.radioKind());
				row.putString("dim", tx.dimension());
				if(tx.radioPos() != null)
					row.putLong("pos", tx.radioPos().asLong());
				row.putFloat("hz", tx.frequency());
				row.putString("mode", tx.mode());
				row.putLong("start", tx.startMs());
				row.putLong("last", tx.lastMs());
				row.putInt("hearers", tx.hearers());
				rows.add(row);
			}
		tag.put("rows", rows);
		tag.putLong("now", System.currentTimeMillis());
		tag.putBoolean("prox", proximity);
		return new Reply("live", tag);
	}

	private static ListTag talkers(List<TransmissionRecord.Talker> talkers) {
		ListTag list = new ListTag();
		for(TransmissionRecord.Talker talker : talkers) {
			CompoundTag entry = new CompoundTag();
			entry.putString("uuid", talker.uuid().toString());
			entry.putString("name", talker.name());
			entry.putString("call", talker.callsign());
			list.add(entry);
		}
		return list;
	}

	/**
	 * Who the asker listens to live: "mode" is "sends", "hears", "voice" (plain voice chat), "frequency" (tuned in, "name"
	 * the frequency in MHz) or empty for nobody; "name" theirs.
	 */
	static Reply listening(ServerPlayer player) {
		CompoundTag tag = new CompoundTag();
		Float tuned = RadioModeration.tunedTo(player.getUUID());
		if(tuned != null) {
			tag.putString("mode", "frequency");
			tag.putString("name", ModerationFormat.mhz(tuned));
			return new Reply("listening", tag);
		}
		UUID sends = RadioModeration.listeningTo(player.getUUID()), hears = RadioModeration.hearingThrough(player.getUUID());
		UUID voice = RadioModeration.hearingVoiceOf(player.getUUID());
		UUID target = sends != null ? sends : hears != null ? hears : voice;
		tag.putString("mode", sends != null ? "sends" : hears != null ? "hears" : voice != null ? "voice" : "");
		if(target != null) {
			tag.putString("uuid", target.toString());
			ServerPlayer online = player.server.getPlayerList().getPlayer(target);
			tag.putString("name", RadioModeration.EVERYONE.equals(target) ? "*" : online != null ? online.getGameProfile().getName() : target.toString().substring(0, 8));
		}
		return new Reply("listening", tag);
	}

	/**
	 * Starts listening live: to what a player sends over radios ("mode" sends), hears on their radios ("hears") or says
	 * in plain voice chat ("voice"), the player by "uuid" or by "name"; or to everything sent on a frequency ("frequency",
	 * "hz"). Audited as "listen".
	 */
	private static void listen(@Nullable ModerationLog log, ServerPlayer player, CompoundTag data, Consumer<Reply> out) {
		String mode = data.getString("mode");
		if("frequency".equals(mode)) { // Tuned in to a frequency ("hz"), whoever sends there.
			float hz = data.getFloat("hz");
			if(hz <= 0.0F)
				return;
			RadioModeration.listenFrequency(player, hz);
			audit(log, player, "listen", 0L, "frequency " + ModerationFormat.mhz(hz));
			out.accept(listening(player));
			return;
		}
		boolean hears = "hears".equals(mode), voice = "voice".equals(mode);
		UUID uuid = uuid(data.getString("uuid"));
		Consumer<GameProfile> start = profile -> {
			if(profile == null) {
				out.accept(error("unknown_player", data.getString("name")));
				return;
			}
			if(hears)
				RadioModeration.listenHears(player, profile.getId());
			else if(voice)
				RadioModeration.listenVoice(player, profile.getId());
			else
				RadioModeration.listen(player, profile.getId());
			audit(log, player, "listen", 0L, (hears ? "hears " : voice ? "voice " : "sends ") + profile.getName());
			out.accept(listening(player));
		};
		if(uuid != null) {
			ServerPlayer online = player.server.getPlayerList().getPlayer(uuid);
			start.accept(new GameProfile(uuid, online != null ? online.getGameProfile().getName() : data.getString("name")));
		}
		else if(!data.getString("name").isBlank())
			resolve(player.server, data.getString("name").trim(), start);
	}

	// -------------------- ARCHIVE --------------------

	/** The time range a request names ("range", a {@link ModerationFormat.Range} ordinal), on the server's clock. */
	private static long[] range(CompoundTag data) {
		return ModerationFormat.range(ModerationFormat.Range.byOrdinal(data.getInt("range")), System.currentTimeMillis(), ZoneId.systemDefault());
	}

	/**
	 * The query a request describes: "player" (resolved to {@code player}) as "role" (a {@link TransmissionQuery.PlayerRole}
	 * ordinal), "fmin"/"fmax" Hz, "pos" (a radio's position) and "dim", "range", "kinds" (a {@link ModerationFormat#mask}),
	 * "text", "flagged", "offset" and "limit"; with "prox", plain voice chat only.
	 */
	static TransmissionQuery query(CompoundTag data, @Nullable UUID player) {
		long[] range = range(data);
		TransmissionQuery query = TransmissionQuery.all().withTime(range[0], range[1]);
		if(player != null) {
			TransmissionQuery.PlayerRole[] roles = TransmissionQuery.PlayerRole.values();
			int role = data.getInt("role");
			query = query.withPlayer(player, role >= 0 && role < roles.length ? roles[role] : TransmissionQuery.PlayerRole.EITHER);
		}
		if(data.contains("fmin") || data.contains("fmax"))
			query = query.withFrequency(data.contains("fmin") ? data.getFloat("fmin") : 0.0F, data.contains("fmax") ? data.getFloat("fmax") : Float.MAX_VALUE);
		String dim = data.getString("dim");
		if(data.contains("pos"))
			query = query.withRadio(dim.isEmpty() ? null : dim, BlockPos.of(data.getLong("pos")));
		else if(!dim.isEmpty())
			query = query.withDimension(dim);
		Set<TransmissionKind> kinds = ModerationFormat.kinds(data.getInt("kinds"));
		if(data.getBoolean("prox"))
			query = query.withKinds(TransmissionKind.PROXIMITY);
		else {
			kinds.remove(TransmissionKind.PROXIMITY);
			if(!kinds.isEmpty()) {
				TransmissionKind[] all = kinds.toArray(TransmissionKind[]::new);
				query = query.withKinds(all[0], java.util.Arrays.copyOfRange(all, 1, all.length));
			}
		}
		if(!data.getString("text").isBlank())
			query = query.withText(data.getString("text").trim());
		int limit = data.contains("limit") ? data.getInt("limit") : MAX_PAGE;
		return query.withFlaggedOnly(data.getBoolean("flagged")).withPage(Math.max(0, data.getInt("offset")), Math.max(1, Math.min(MAX_PAGE, limit)));
	}

	private static Reply page(ModerationLog log, CompoundTag data, @Nullable UUID player) {
		TransmissionQuery query = query(data, player);
		TransmissionQuery.Page page = log.query(query);
		CompoundTag tag = new CompoundTag();
		ListTag rows = new ListTag();
		for(TransmissionRecord record : page.items())
			rows.add(row(record));
		tag.put("rows", rows);
		tag.putInt("total", page.total());
		tag.putInt("offset", query.offset());
		tag.putInt("limit", query.limit());
		tag.putBoolean("prox", data.getBoolean("prox"));
		return new Reply("page", tag);
	}

	private static Reply emptyPage(boolean proximity) {
		CompoundTag tag = new CompoundTag();
		tag.put("rows", new ListTag());
		tag.putInt("total", 0);
		tag.putBoolean("prox", proximity);
		return new Reply("page", tag);
	}

	/** A transmission as one line of the archive's list. */
	static CompoundTag row(TransmissionRecord record) {
		CompoundTag row = new CompoundTag();
		row.putLong("id", record.id());
		row.putString("kind", record.kind().id());
		row.putLong("start", record.startMs());
		row.putLong("length", record.durationMs());
		StringBuilder names = new StringBuilder();
		for(TransmissionRecord.Talker talker : record.talkers())
			names.append(names.isEmpty() ? "" : ", ").append(talker.name().isEmpty() ? talker.uuid().toString().substring(0, 8) : talker.name());
		row.putString("talkers", ModerationFormat.clip(names.toString(), 60));
		row.putString("radio", record.radioKind());
		row.putString("dim", record.dimension());
		if(record.radioPos() != null)
			row.putLong("pos", record.radioPos().asLong());
		row.putFloat("hz", record.frequency());
		row.putString("mode", record.mode());
		row.putInt("hearers", record.hearers().size());
		row.putBoolean("audio", record.hasAudio() || record.tapeRecording() != null);
		row.putBoolean("flagged", record.flagged());
		row.putString("text", ModerationFormat.clip(record.text(), 80));
		return row;
	}

	/** A transmission in full, for the detail pane: its row, the talkers, the hearers with strength, packets and notes. */
	static CompoundTag detail(TransmissionRecord record) {
		CompoundTag tag = row(record);
		tag.put("talkerList", talkers(record.talkers()));
		ListTag hearers = new ListTag();
		for(TransmissionRecord.Hearer hearer : record.hearers()) {
			if(hearers.size() >= MAX_HEARERS)
				break;
			CompoundTag entry = new CompoundTag();
			entry.putString("name", hearer.playerName());
			entry.putString("radio", hearer.radioKind());
			entry.putString("dim", hearer.dimension());
			if(hearer.pos() != null)
				entry.putLong("pos", hearer.pos().asLong());
			entry.putFloat("strength", hearer.strength());
			hearers.add(entry);
		}
		tag.put("hearerList", hearers);
		tag.putInt("hearerCount", record.hearers().size());
		ListTag packets = new ListTag();
		for(TransmissionRecord.DataPacket packet : record.packets()) {
			if(packets.size() >= MAX_PACKETS)
				break;
			packets.add(StringTag.valueOf(ModerationFormat.clip(packet.summary(), 160)));
		}
		tag.put("packets", packets);
		tag.putInt("packetCount", record.packets().size());
		tag.putString("fullText", ModerationFormat.clip(record.text(), 2000));
		tag.putString("band", record.band());
		tag.putFloat("ctcss", record.ctcss());
		tag.putLong("linked", record.linkedTo());
		tag.putInt("frames", record.audioFrames());
		if(record.position() != null)
			tag.putLong("antenna", record.position().asLong());
		if(record.tapeRecording() != null)
			tag.putString("tape", record.tapeRecording().toString());
		return tag;
	}

	/** The files an export wrote, as paths from the server's folder where they are under it. */
	private static Reply exported(MinecraftServer server, ModerationLog log, @Nullable List<Path> files) {
		CompoundTag tag = new CompoundTag();
		ListTag list = new ListTag();
		if(files != null)
			for(Path file : files)
				list.add(StringTag.valueOf(shown(server, file)));
		tag.put("files", list);
		tag.putString("folder", shown(server, log.folder().resolve("exports")));
		tag.putBoolean("unknown", files == null);
		return new Reply("exported", tag);
	}

	private static String shown(MinecraftServer server, Path file) {
		try {
			Path base = server.getServerDirectory().toAbsolutePath().normalize();
			Path path = file.toAbsolutePath().normalize();
			return path.startsWith(base) ? base.relativize(path).toString() : path.toString();
		}
		catch(RuntimeException e) {
			return file.toString();
		}
	}

	/** Answers {@code future}'s result through {@code out} on the server thread; a failure answers "error". */
	private static <T> void later(MinecraftServer server, CompletableFuture<T> future, Consumer<Reply> out, Function<T, Reply> answer) {
		future.whenComplete((result, error) -> server.execute(() -> out.accept(error != null ? error("failed", String.valueOf(error.getMessage())) : answer.apply(result))));
	}

	// -------------------- MAP, LINKS, TAPES, AUDIT --------------------

	/**
	 * Who heard whom in the range, the days added up: up to {@link #MAX_LINKS} links, busiest first, and the nodes they
	 * join ("player" ones by name, radios by kind and position).
	 */
	static Reply links(@Nullable ModerationLog log, CompoundTag data) {
		CompoundTag tag = new CompoundTag();
		ListTag nodes = new ListTag(), links = new ListTag();
		if(running(log)) {
			long[] range = range(data);
			Map<List<String>, Integer> counts = new LinkedHashMap<>();
			Map<String, LinkGraph.Node> byId = new HashMap<>();
			for(LinkGraph.Link link : log.links(range[0], range[1], data.getBoolean("prox"))) {
				byId.putIfAbsent(link.from().id(), link.from());
				byId.putIfAbsent(link.to().id(), link.to());
				counts.merge(List.of(link.from().id(), link.to().id()), link.count(), Integer::sum);
			}
			List<Map.Entry<List<String>, Integer>> busiest = new ArrayList<>(counts.entrySet());
			busiest.sort(Map.Entry.<List<String>, Integer>comparingByValue().reversed());
			Map<String, Integer> index = new LinkedHashMap<>();
			for(Map.Entry<List<String>, Integer> entry : busiest.subList(0, Math.min(MAX_LINKS, busiest.size()))) {
				CompoundTag link = new CompoundTag();
				link.putInt("from", index.computeIfAbsent(entry.getKey().get(0), id -> index.size()));
				link.putInt("to", index.computeIfAbsent(entry.getKey().get(1), id -> index.size()));
				link.putInt("count", entry.getValue());
				links.add(link);
			}
			for(String id : index.keySet()) {
				LinkGraph.Node node = byId.get(id);
				CompoundTag entry = new CompoundTag();
				entry.putString("label", node.label());
				entry.putBoolean("player", node.isPlayer());
				nodes.add(entry);
			}
		}
		tag.put("nodes", nodes);
		tag.put("links", links);
		return new Reply("links", tag);
	}

	/**
	 * The radios on the air in the range in one dimension ("dim", empty for the asker's): where, what, how often they
	 * sent and heard, and the last talker and frequency each sent on. Most recently active first, at most
	 * {@link #MAX_SITES}.
	 */
	static Reply radios(@Nullable ModerationLog log, MinecraftServer server, CompoundTag data) {
		CompoundTag tag = new CompoundTag();
		ListTag sites = new ListTag();
		String dim = data.getString("dim");
		if(running(log)) {
			long[] range = range(data);
			List<ModerationLog.RadioSite> found = new ArrayList<>(log.radios(range[0], range[1], dim.isEmpty() ? null : dim));
			found.sort(Comparator.comparingLong(ModerationLog.RadioSite::lastMs).reversed());
			record Last(String talker, float hz) {}
			Map<BlockPos, Last> last = new HashMap<>();
			TransmissionQuery recent = TransmissionQuery.all().withTime(range[0], range[1]).withDimension(dim.isEmpty() ? null : dim).withPage(0, SITE_SCAN);
			for(TransmissionRecord record : log.query(recent).items())
				if(record.radioPos() != null && !last.containsKey(record.radioPos()))
					last.put(record.radioPos(), new Last(record.talker() == null ? "" : record.talker().name(), record.frequency()));
			for(ModerationLog.RadioSite site : found.subList(0, Math.min(MAX_SITES, found.size()))) {
				CompoundTag entry = new CompoundTag();
				entry.putString("dim", site.dimension());
				entry.putLong("pos", site.pos().asLong());
				entry.putString("radio", site.radioKind());
				entry.putInt("sent", site.sent());
				entry.putInt("heard", site.heard());
				entry.putLong("last", site.lastMs());
				Last known = last.get(site.pos());
				if(known != null) {
					entry.putString("talker", known.talker());
					entry.putFloat("hz", known.hz());
				}
				sites.add(entry);
			}
		}
		tag.put("sites", sites);
		tag.putString("dim", dim);
		return new Reply("radios", tag);
	}

	/** Player tapes recorded or played in the range, by one speaker if "player" named one. */
	private static Reply tapes(@Nullable ModerationLog log, CompoundTag data, @Nullable UUID speaker) {
		CompoundTag tag = new CompoundTag();
		ListTag rows = new ListTag();
		if(running(log)) {
			long[] range = range(data);
			for(ModerationLog.TapeEntry tape : log.tapes(speaker, range[0], range[1])) {
				if(rows.size() >= MAX_TAPES)
					break;
				CompoundTag row = new CompoundTag();
				row.putString("recording", tape.recording().toString());
				row.putLong("recorded", tape.recordedMs());
				row.putInt("seconds", tape.seconds());
				row.putString("dim", tape.dimension());
				if(tape.pos() != null)
					row.putLong("pos", tape.pos().asLong());
				StringBuilder names = new StringBuilder();
				for(TransmissionRecord.Talker talker : tape.speakers())
					names.append(names.isEmpty() ? "" : ", ").append(talker.name());
				row.putString("speakers", ModerationFormat.clip(names.toString(), 60));
				row.putInt("plays", tape.plays());
				row.putLong("lastPlayed", tape.lastPlayedMs());
				row.putLong("play", tape.recordId() != 0L ? tape.recordId() : tape.lastPlay());
				rows.add(row);
			}
		}
		tag.put("rows", rows);
		return new Reply("tapes", tag);
	}

	/** The audit log in the range, newest first, at most {@link #MAX_AUDIT} lines. */
	static Reply auditLog(@Nullable ModerationLog log, CompoundTag data) {
		CompoundTag tag = new CompoundTag();
		ListTag rows = new ListTag();
		if(running(log)) {
			long[] range = range(data);
			for(ModerationStore.AuditEntry entry : log.auditLog(range[0], range[1], MAX_AUDIT)) {
				CompoundTag row = new CompoundTag();
				row.putLong("ms", entry.ms());
				row.putString("who", entry.whoName());
				row.putString("action", entry.action());
				row.putLong("target", entry.target());
				row.putString("detail", ModerationFormat.clip(entry.detail(), 80));
				rows.add(row);
			}
		}
		tag.put("rows", rows);
		return new Reply("audit", tag);
	}

	// -------------------- MESSAGES --------------------

	static Reply message(String key, String arg) {
		CompoundTag tag = new CompoundTag();
		tag.putString("key", key);
		tag.putString("arg", arg);
		return new Reply("message", tag);
	}

	static Reply error(String key, String arg) {
		CompoundTag tag = new CompoundTag();
		tag.putString("key", key);
		tag.putString("arg", arg);
		return new Reply("error", tag);
	}
}
