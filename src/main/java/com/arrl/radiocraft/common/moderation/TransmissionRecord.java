package com.arrl.radiocraft.common.moderation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One logged transmission, closed: who keyed up, where, on what, who heard it and what was kept. This is one line of a
 * day's index file (JSON) and what every query of the {@link ModerationLog} hands back. Immutable.
 *
 * @param id             unique and in time order: {@code startMs * 1000 +} a counter (see {@link #startMsOf})
 * @param kind           what it was, and so what was kept
 * @param startMs        first frame, epoch ms
 * @param endMs          last frame, epoch ms
 * @param startGameTime  the overworld's game time at the first frame
 * @param dimension      dimension id ("minecraft:overworld")
 * @param talkers        who spoke (index = the audio stream number); empty for blocks that sent by themselves
 * @param radioKind      what sent it: a block's id path ("vhf_base_station"), or handheld, desk_charger, manpack,
 *                       commercial_module, tape_player, proximity
 * @param radioPos       the transmitting radio (or player, for handhelds and proximity), null if unknown
 * @param position       where the signal left from (the antenna), null if unknown
 * @param frequency      Hz; 0 for proximity
 * @param band           band name ("2m"), empty if none
 * @param mode           FM, USB, LSB, CW, DATA, or empty
 * @param ctcss          CTCSS tone sent under the voice, 0 for none
 * @param hearers        every radio (and player) that heard it, with the strongest strength
 * @param linkedTo       for {@link TransmissionKind#REPEATED}: the id of the transmission repeated; 0 otherwise
 * @param text           a line for the list: DTMF keys, decoded CW, RTTY text, the program or tape played
 * @param packets        data packets sent ({@link TransmissionKind#DATA}), in order
 * @param cw             CW key buffers ({@link TransmissionKind#CW}), in order
 * @param audio          where its audio frames are, in order; empty if none were kept
 * @param audioFrames    Opus frames kept
 * @param tapeRecording  for {@link TransmissionKind#TAPE} and {@link TransmissionKind#TAPE_RECORDING}: the recording's id
 * @param flagged        kept past retention (copied to the kept folder)
 */
public record TransmissionRecord(long id, TransmissionKind kind, long startMs, long endMs, long startGameTime, String dimension,
		List<Talker> talkers, String radioKind, @Nullable BlockPos radioPos, @Nullable BlockPos position, float frequency, String band,
		String mode, float ctcss, List<Hearer> hearers, long linkedTo, String text, List<DataPacket> packets, List<CwBuffer> cw,
		List<AudioSpan> audio, int audioFrames, @Nullable UUID tapeRecording, boolean flagged) {

	/** A player who spoke in a transmission. {@code callsign} is empty if they have none. */
	public record Talker(UUID uuid, String name, String callsign) {}

	/**
	 * Something that heard a transmission: a radio, and when a player was listening, that player (a handheld's or
	 * manpack's holder, a player near a block radio's speaker, or a player in voice chat range for proximity).
	 *
	 * @param player     the listening player, null for a radio nobody was near
	 * @param playerName their name, empty if none
	 * @param radioKind  the receiving radio's kind (as {@link TransmissionRecord#radioKind})
	 * @param dimension  dimension id
	 * @param pos        the receiving radio, or the player for handhelds and proximity
	 * @param strength   the strongest signal it heard, 0 to 1, after the receiver's own losses: what playback of
	 *                   "what X heard" puts through the receiver effects
	 * @param firstMs    when it first heard it, epoch ms
	 */
	public record Hearer(@Nullable UUID player, String playerName, String radioKind, String dimension, @Nullable BlockPos pos, float strength, long firstMs) {}

	/** A data packet: {@code ms} when it went out, the packet's kind, its saved record (SNBT, {@code TncPacket#save}) and a readable line. */
	public record DataPacket(long ms, String kind, String snbt, String summary) {}

	/** A CW buffer: its number and the key states of its 20 ticks packed as bits (as {@code CWBuffer#toMask}). */
	public record CwBuffer(int id, int mask) {}

	/** Bytes {@code start} (inclusive) to {@code end} (exclusive) of {@code file} (relative to the moderation folder) hold this transmission's frames, among others'. */
	public record AudioSpan(String file, long start, long end) {}

	/** Epoch ms a transmission with this id started. */
	public static long startMsOf(long id) {
		return id / 1000L;
	}

	/** The first talker, or null if nobody spoke. */
	@Nullable
	public Talker talker() {
		return talkers.isEmpty() ? null : talkers.getFirst();
	}

	/** True if {@code player} spoke in it. */
	public boolean spokenBy(UUID player) {
		for(Talker talker : talkers)
			if(talker.uuid().equals(player))
				return true;
		return false;
	}

	/** True if {@code player} heard it (as a radio's holder or a listener near one). */
	public boolean heardBy(UUID player) {
		for(Hearer hearer : hearers)
			if(player.equals(hearer.player()))
				return true;
		return false;
	}

	public long durationMs() {
		return Math.max(0L, endMs - startMs);
	}

	/** True if audio frames were kept for it. */
	public boolean hasAudio() {
		return audioFrames > 0 && !audio.isEmpty();
	}

	public TransmissionRecord withFlagged(boolean value, List<AudioSpan> spans) {
		return new TransmissionRecord(id, kind, startMs, endMs, startGameTime, dimension, talkers, radioKind, radioPos, position, frequency, band, mode, ctcss,
				hearers, linkedTo, text, packets, cw, spans, audioFrames, tapeRecording, value);
	}

	// -------------------- JSON --------------------

	/** This record as a JSON object, one line of a day's index. */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		json.addProperty("id", id);
		json.addProperty("kind", kind.id());
		json.addProperty("start", startMs);
		json.addProperty("end", endMs);
		json.addProperty("tick", startGameTime);
		json.addProperty("dim", dimension);
		JsonArray talkerArray = new JsonArray();
		for(Talker talker : talkers) {
			JsonObject t = new JsonObject();
			t.addProperty("uuid", talker.uuid().toString());
			t.addProperty("name", talker.name());
			if(!talker.callsign().isEmpty())
				t.addProperty("call", talker.callsign());
			talkerArray.add(t);
		}
		json.add("talkers", talkerArray);
		json.addProperty("radio", radioKind);
		putPos(json, "radioPos", radioPos);
		putPos(json, "pos", position);
		json.addProperty("hz", frequency);
		json.addProperty("band", band);
		json.addProperty("mode", mode);
		if(ctcss != 0.0F)
			json.addProperty("ctcss", ctcss);
		JsonArray hearerArray = new JsonArray();
		for(Hearer hearer : hearers) {
			JsonObject h = new JsonObject();
			if(hearer.player() != null) {
				h.addProperty("player", hearer.player().toString());
				h.addProperty("name", hearer.playerName());
			}
			h.addProperty("radio", hearer.radioKind());
			h.addProperty("dim", hearer.dimension());
			putPos(h, "pos", hearer.pos());
			h.addProperty("strength", hearer.strength());
			h.addProperty("first", hearer.firstMs());
			hearerArray.add(h);
		}
		json.add("hearers", hearerArray);
		if(linkedTo != 0L)
			json.addProperty("linked", linkedTo);
		if(!text.isEmpty())
			json.addProperty("text", text);
		if(!packets.isEmpty()) {
			JsonArray array = new JsonArray();
			for(DataPacket packet : packets) {
				JsonObject p = new JsonObject();
				p.addProperty("ms", packet.ms());
				p.addProperty("kind", packet.kind());
				p.addProperty("snbt", packet.snbt());
				p.addProperty("summary", packet.summary());
				array.add(p);
			}
			json.add("packets", array);
		}
		if(!cw.isEmpty()) {
			JsonArray array = new JsonArray();
			for(CwBuffer buffer : cw) {
				JsonArray b = new JsonArray();
				b.add(buffer.id());
				b.add(buffer.mask());
				array.add(b);
			}
			json.add("cw", array);
		}
		if(!audio.isEmpty()) {
			JsonArray array = new JsonArray();
			for(AudioSpan span : audio) {
				JsonObject s = new JsonObject();
				s.addProperty("f", span.file());
				s.addProperty("s", span.start());
				s.addProperty("e", span.end());
				array.add(s);
			}
			json.add("audio", array);
			json.addProperty("frames", audioFrames);
		}
		if(tapeRecording != null)
			json.addProperty("tape", tapeRecording.toString());
		if(flagged)
			json.addProperty("flagged", true);
		return json;
	}

	/** Reads a record written by {@link #toJson}. Throws on a line that is not one. */
	public static TransmissionRecord fromJson(JsonObject json) {
		List<Talker> talkers = new ArrayList<>();
		for(JsonElement element : array(json, "talkers")) {
			JsonObject t = element.getAsJsonObject();
			talkers.add(new Talker(UUID.fromString(t.get("uuid").getAsString()), string(t, "name"), string(t, "call")));
		}
		List<Hearer> hearers = new ArrayList<>();
		for(JsonElement element : array(json, "hearers")) {
			JsonObject h = element.getAsJsonObject();
			hearers.add(new Hearer(h.has("player") ? UUID.fromString(h.get("player").getAsString()) : null, string(h, "name"), string(h, "radio"),
					string(h, "dim"), pos(h, "pos"), h.has("strength") ? h.get("strength").getAsFloat() : 0.0F, h.has("first") ? h.get("first").getAsLong() : 0L));
		}
		List<DataPacket> packets = new ArrayList<>();
		for(JsonElement element : array(json, "packets")) {
			JsonObject p = element.getAsJsonObject();
			packets.add(new DataPacket(p.get("ms").getAsLong(), string(p, "kind"), string(p, "snbt"), string(p, "summary")));
		}
		List<CwBuffer> cw = new ArrayList<>();
		for(JsonElement element : array(json, "cw")) {
			JsonArray b = element.getAsJsonArray();
			cw.add(new CwBuffer(b.get(0).getAsInt(), b.get(1).getAsInt()));
		}
		List<AudioSpan> audio = new ArrayList<>();
		for(JsonElement element : array(json, "audio")) {
			JsonObject s = element.getAsJsonObject();
			audio.add(new AudioSpan(s.get("f").getAsString(), s.get("s").getAsLong(), s.get("e").getAsLong()));
		}
		return new TransmissionRecord(json.get("id").getAsLong(), TransmissionKind.byId(string(json, "kind")), json.get("start").getAsLong(), json.get("end").getAsLong(),
				json.has("tick") ? json.get("tick").getAsLong() : 0L, string(json, "dim"), List.copyOf(talkers), string(json, "radio"), pos(json, "radioPos"), pos(json, "pos"),
				json.has("hz") ? json.get("hz").getAsFloat() : 0.0F, string(json, "band"), string(json, "mode"), json.has("ctcss") ? json.get("ctcss").getAsFloat() : 0.0F,
				List.copyOf(hearers), json.has("linked") ? json.get("linked").getAsLong() : 0L, string(json, "text"), List.copyOf(packets), List.copyOf(cw), List.copyOf(audio),
				json.has("frames") ? json.get("frames").getAsInt() : 0, json.has("tape") ? UUID.fromString(json.get("tape").getAsString()) : null,
				json.has("flagged") && json.get("flagged").getAsBoolean());
	}

	private static void putPos(JsonObject json, String key, @Nullable BlockPos pos) {
		if(pos == null)
			return;
		JsonArray array = new JsonArray();
		array.add(pos.getX());
		array.add(pos.getY());
		array.add(pos.getZ());
		json.add(key, array);
	}

	@Nullable
	private static BlockPos pos(JsonObject json, String key) {
		if(!json.has(key) || !json.get(key).isJsonArray())
			return null;
		JsonArray array = json.getAsJsonArray(key);
		return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
	}

	private static String string(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
	}

	private static JsonArray array(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
	}
}
