package com.arrl.radiocraft.common.datacomponents;

import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The APRS module in a handheld (its presence on the item is the module): the stations it has heard beacon, the
 * messages it has had and sent, and its settings. See {@link com.arrl.radiocraft.common.radio.data.HandheldAprsStation}.
 *
 * @param settings       The beacon (on, icon, comment, SSID, interval) and the mute.
 * @param stations       Stations heard, newest first, one per callsign.
 * @param messages       Messages in and out, newest first.
 * @param nextNumber     Message number the next message sent goes out with.
 * @param lastBeacon     Game time of the last beacon, -1 if none.
 * @param callsign       The holder's callsign (without SSID) as the server last saw it, for the screen; empty if none.
 */
public record HandheldAprs(Settings settings, List<Station> stations, List<Message> messages, int nextNumber, long lastBeacon, String callsign) {

	/** Stations and messages each keep the last this many. */
	public static final int HISTORY = 12;

	/** Choices for the beacon interval, in seconds. */
	public static final int[] INTERVALS = { 30, 60, 120, 300, 600, 1800 };
	/** Highest SSID (the number after the callsign's dash; 0 for none). */
	public static final int MAX_SSID = 15;
	/** SSID a handheld goes out with until its holder changes it: -7, as real handhelds use. */
	public static final int DEFAULT_SSID = 7;

	/**
	 * What the module sends and plays.
	 *
	 * @param beacon   Beacons the holder's position on a timer.
	 * @param mute     Keeps APRS bursts out of the speaker (voice still plays).
	 * @param icon     The {@link com.arrl.radiocraft.common.radio.data.AprsIcon} the beacon shows.
	 * @param comment  The beacon's comment.
	 * @param ssid     Added to the holder's callsign after a dash (W1ABC-7); 0 for none.
	 * @param interval Seconds between beacons.
	 */
	public record Settings(boolean beacon, boolean mute, String icon, String comment, int ssid, int interval) {

		public static final Settings DEFAULT = new Settings(false, false, "PORTABLE", "", DEFAULT_SSID, 120);

		public static final Codec<Settings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("beacon", false).forGetter(Settings::beacon),
				Codec.BOOL.optionalFieldOf("mute", false).forGetter(Settings::mute),
				Codec.STRING.optionalFieldOf("icon", "PORTABLE").forGetter(Settings::icon),
				Codec.STRING.optionalFieldOf("comment", "").forGetter(Settings::comment),
				Codec.INT.optionalFieldOf("ssid", DEFAULT_SSID).forGetter(Settings::ssid),
				Codec.INT.optionalFieldOf("interval", 120).forGetter(Settings::interval)
		).apply(instance, Settings::new));

		public static final StreamCodec<ByteBuf, Settings> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, Settings::beacon,
				ByteBufCodecs.BOOL, Settings::mute,
				ByteBufCodecs.STRING_UTF8, Settings::icon,
				ByteBufCodecs.STRING_UTF8, Settings::comment,
				ByteBufCodecs.VAR_INT, Settings::ssid,
				ByteBufCodecs.VAR_INT, Settings::interval,
				Settings::new);

		/** Every value made valid: a known icon, printable comment no longer than a beacon carries, SSID 0-15, a listed interval. */
		public Settings sanitized() {
			int seconds = INTERVALS[0];
			for(int choice : INTERVALS)
				if(choice <= interval)
					seconds = choice;
			return new Settings(beacon, mute, com.arrl.radiocraft.common.radio.data.AprsIcon.byName(icon).name(),
					TncPacket.sanitizeText(comment, TncPacket.BEACON_TEXT_MAX), Math.max(0, Math.min(MAX_SSID, ssid)), seconds);
		}

		public Settings withBeacon(boolean on) {
			return new Settings(on, mute, icon, comment, ssid, interval);
		}

		public Settings withMute(boolean on) {
			return new Settings(beacon, on, icon, comment, ssid, interval);
		}
	}

	/** {@code callsign} with the SSID after a dash, or as it is for SSID 0. */
	public static String withSsid(String callsign, int ssid) {
		return callsign.isEmpty() || ssid <= 0 ? callsign : TncPacket.sanitizeCallsign(callsign + "-" + ssid);
	}

	/** {@code callsign} without its SSID (W1ABC-7 is W1ABC). */
	public static String baseCallsign(String callsign) {
		int dash = callsign.lastIndexOf('-');
		return dash <= 0 ? callsign : callsign.substring(0, dash);
	}

	public boolean beacon() {
		return settings.beacon();
	}

	public boolean mute() {
		return settings.mute();
	}

	/** A station heard beaconing: where it was, its icon and comment, and when (game time). */
	public record Station(String callsign, BlockPos pos, String icon, String comment, long heardAt) {

		public static final Codec<Station> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("callsign").forGetter(Station::callsign),
				BlockPos.CODEC.fieldOf("pos").forGetter(Station::pos),
				Codec.STRING.optionalFieldOf("icon", "HOUSE").forGetter(Station::icon),
				Codec.STRING.optionalFieldOf("comment", "").forGetter(Station::comment),
				Codec.LONG.optionalFieldOf("heardAt", 0L).forGetter(Station::heardAt)
		).apply(instance, Station::new));

		public static final StreamCodec<ByteBuf, Station> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Station::callsign,
				BlockPos.STREAM_CODEC, Station::pos,
				ByteBufCodecs.STRING_UTF8, Station::icon,
				ByteBufCodecs.STRING_UTF8, Station::comment,
				ByteBufCodecs.VAR_LONG, Station::heardAt,
				Station::new);
	}

	/** Where a message stands: one received, or one sent and waiting for its ack, acked, or sent to everyone. */
	public enum Status { RECEIVED, SENT, ACKED }

	/**
	 * A message received or sent.
	 *
	 * @param peer   Who it came from, or who it went to.
	 * @param number Its message number (what the ack answers); empty for a bulletin.
	 * @param read   False until a received message has been opened.
	 */
	public record Message(String peer, String text, String number, Status status, boolean read, long time) {

		public static final Codec<Message> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.fieldOf("peer").forGetter(Message::peer),
				Codec.STRING.fieldOf("text").forGetter(Message::text),
				Codec.STRING.optionalFieldOf("number", "").forGetter(Message::number),
				Codec.STRING.xmap(HandheldAprs::status, Status::name).optionalFieldOf("status", Status.RECEIVED).forGetter(Message::status),
				Codec.BOOL.optionalFieldOf("read", true).forGetter(Message::read),
				Codec.LONG.optionalFieldOf("time", 0L).forGetter(Message::time)
		).apply(instance, Message::new));

		public static final StreamCodec<ByteBuf, Message> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.STRING_UTF8, Message::peer,
				ByteBufCodecs.STRING_UTF8, Message::text,
				ByteBufCodecs.STRING_UTF8, Message::number,
				ByteBufCodecs.VAR_INT.map(i -> Status.values()[Math.floorMod(i, Status.values().length)], Status::ordinal), Message::status,
				ByteBufCodecs.BOOL, Message::read,
				ByteBufCodecs.VAR_LONG, Message::time,
				Message::new);

		public boolean incoming() {
			return status == Status.RECEIVED;
		}
	}

	private static Status status(String name) {
		try {
			return Status.valueOf(name);
		}
		catch(IllegalArgumentException e) {
			return Status.RECEIVED;
		}
	}

	/** A freshly installed module: beacon off, sound on, nothing heard. */
	public static final HandheldAprs INSTALLED = new HandheldAprs(Settings.DEFAULT, List.of(), List.of(), 1, -1L, "");

	public static final Codec<HandheldAprs> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Settings.CODEC.optionalFieldOf("settings", Settings.DEFAULT).forGetter(HandheldAprs::settings),
			Station.CODEC.listOf().optionalFieldOf("stations", List.of()).forGetter(HandheldAprs::stations),
			Message.CODEC.listOf().optionalFieldOf("messages", List.of()).forGetter(HandheldAprs::messages),
			Codec.INT.optionalFieldOf("nextNumber", 1).forGetter(HandheldAprs::nextNumber),
			Codec.LONG.optionalFieldOf("lastBeacon", -1L).forGetter(HandheldAprs::lastBeacon),
			Codec.STRING.optionalFieldOf("callsign", "").forGetter(HandheldAprs::callsign)
	).apply(instance, HandheldAprs::new));

	public static final StreamCodec<ByteBuf, HandheldAprs> STREAM_CODEC = StreamCodec.composite(
			Settings.STREAM_CODEC, HandheldAprs::settings,
			Station.STREAM_CODEC.apply(ByteBufCodecs.list(HISTORY)), HandheldAprs::stations,
			Message.STREAM_CODEC.apply(ByteBufCodecs.list(HISTORY)), HandheldAprs::messages,
			ByteBufCodecs.VAR_INT, HandheldAprs::nextNumber,
			ByteBufCodecs.VAR_LONG, HandheldAprs::lastBeacon,
			ByteBufCodecs.STRING_UTF8, HandheldAprs::callsign,
			HandheldAprs::new);

	public HandheldAprs withBeacon(boolean on) {
		return new HandheldAprs(settings.withBeacon(on), stations, messages, nextNumber, on ? -1L : lastBeacon, callsign); // On: beacons right away.
	}

	public HandheldAprs withMute(boolean on) {
		return withSettings(settings.withMute(on));
	}

	/** This module noting the holder's callsign {@code call} (server side, for the screen). */
	public HandheldAprs withCallsign(String call) {
		return new HandheldAprs(settings, stations, messages, nextNumber, lastBeacon, call);
	}

	/** This module with {@code changed} settings (made valid). */
	public HandheldAprs withSettings(Settings changed) {
		return new HandheldAprs(changed.sanitized(), stations, messages, nextNumber, lastBeacon, callsign);
	}

	public HandheldAprs withLastBeacon(long gameTime) {
		return new HandheldAprs(settings, stations, messages, nextNumber, gameTime, callsign);
	}

	/** This module having heard {@code station} beacon: it goes to the top, replacing any older entry for it. */
	public HandheldAprs heard(Station station) {
		List<Station> out = new ArrayList<>(stations);
		out.removeIf(old -> old.callsign().equals(station.callsign()));
		out.addFirst(station);
		trim(out);
		return new HandheldAprs(settings, List.copyOf(out), messages, nextNumber, lastBeacon, callsign);
	}

	/** This module with {@code message} logged at the top. */
	public HandheldAprs logged(Message message) {
		List<Message> out = new ArrayList<>(messages);
		out.addFirst(message);
		trim(out);
		return new HandheldAprs(settings, stations, List.copyOf(out), nextNumber, lastBeacon, callsign);
	}

	/** This module with its next message number used up (1 to 99999, then round again). */
	public HandheldAprs numberUsed() {
		return new HandheldAprs(settings, stations, messages, nextNumber % 99999 + 1, lastBeacon, callsign);
	}

	/** This module with message {@code index} changed by {@code change}. */
	public HandheldAprs withMessage(int index, UnaryOperator<Message> change) {
		if(index < 0 || index >= messages.size())
			return this;
		List<Message> out = new ArrayList<>(messages);
		out.set(index, change.apply(out.get(index)));
		return new HandheldAprs(settings, stations, List.copyOf(out), nextNumber, lastBeacon, callsign);
	}

	/** True if a message has come in that has not been opened. */
	public boolean hasUnread() {
		return messages.stream().anyMatch(message -> message.incoming() && !message.read());
	}

	/** True if we already have message {@code number} from {@code from} (a retry of one we have). */
	public boolean hasMessage(String from, String number) {
		return !number.isEmpty() && messages.stream().anyMatch(message -> message.incoming() && message.peer().equals(from) && message.number().equals(number));
	}

	/** The message we sent to {@code to} as {@code number} marked acked; null if there is none waiting. */
	public HandheldAprs acked(String to, String number) {
		for(int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			if(message.status() == Status.SENT && message.peer().equals(to) && message.number().equals(number))
				return withMessage(i, old -> new Message(old.peer(), old.text(), old.number(), Status.ACKED, true, old.time()));
		}
		return null;
	}

	/** True for a callsign a station could have: what {@link TncPacket#sanitizeCallsign} leaves of it is not empty. */
	public static boolean isCallsign(String callsign) {
		return !TncPacket.sanitizeCallsign(callsign).isEmpty();
	}

	private static void trim(List<?> list) {
		while(list.size() > HISTORY)
			list.removeLast();
	}
}
