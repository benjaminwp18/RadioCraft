package com.arrl.radiocraft.common.radio.data;

import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs.Message;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs.Station;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs.Status;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * What the APRS module in a handheld does with the packets it hears and what it sends: the handheld's side of APRS,
 * kept apart from the radio so it can be tested. Each call takes the module's state and returns the new one, adding
 * any packets to put on the air to {@code out}.
 */
public final class HandheldAprsStation {

	private HandheldAprsStation() {}

	/** The canned messages the handheld can send (it has no keyboard). */
	public static final List<String> CANNED = List.of("QSL", "73", "ON MY WAY", "AT BASE", "NEED HELP", "CALL ME", "QRV?", "WHERE R U?", "OK", "QRT");

	/**
	 * A packet the module decoded, as the station {@code myCall} (with its SSID): a beacon goes on the station list, a
	 * message for us (to {@code myCall}, or to the callsign without the SSID, as an H19 user would type it) is logged (and acked, every time, since our last ack may have been lost) and an ack marks our message acked.
	 * Our own packets coming back off a digipeater are ignored.
	 */
	public static HandheldAprs receive(HandheldAprs aprs, TncPacket packet, String myCall, long now, List<TncPacket> out, LongSupplier ids) {
		if(!myCall.isEmpty() && packet.from().equals(myCall))
			return aprs;
		switch(packet.kind()) {
			case BEACON -> {
				if(packet.position() == null)
					return aprs;
				return aprs.heard(new Station(packet.from(), packet.position(), packet.icon(), packet.text(), now));
			}
			case MESSAGE -> {
				boolean forUs = isMine(packet.to(), myCall);
				if(!forUs && !TncPacket.isForEveryone(packet.to()))
					return aprs;
				String number = forUs ? packet.messageNumber() : "";
				if(!number.isEmpty())
					out.add(TncPacket.ack(ids.getAsLong(), myCall, packet.from(), number));
				if(aprs.hasMessage(packet.from(), number))
					return aprs; // A retry of one we have.
				return aprs.logged(new Message(packet.from(), packet.messageBody(), number, Status.RECEIVED, false, now));
			}
			case ACK -> {
				if(!isMine(packet.to(), myCall))
					return aprs;
				HandheldAprs acked = aprs.acked(packet.from(), packet.text());
				return acked == null ? aprs : acked;
			}
			default -> {
				return aprs;
			}
		}
	}

	/** True if a packet to {@code to} is for the station {@code myCall}: its full callsign, or the callsign without its SSID. */
	public static boolean isMine(String to, String myCall) {
		return !myCall.isEmpty() && (to.equals(myCall) || to.equals(HandheldAprs.baseCallsign(myCall)));
	}

	/** Sends {@code text} to {@code to} (or to everyone, as a bulletin with no ack) as {@code myCall} and logs it. */
	public static HandheldAprs send(HandheldAprs aprs, String myCall, String to, String text, long now, List<TncPacket> out, LongSupplier ids) {
		String target = TncPacket.sanitizeCallsign(to);
		if(myCall.isEmpty() || target.isEmpty() || TncPacket.sanitizeText(text, TncPacket.MESSAGE_TEXT_MAX).isEmpty())
			return aprs;
		boolean everyone = TncPacket.isForEveryone(target);
		TncPacket packet = everyone ? TncPacket.message(ids.getAsLong(), myCall, target, text) : TncPacket.message(ids.getAsLong(), myCall, target, text, aprs.nextNumber());
		out.add(packet);
		HandheldAprs logged = aprs.logged(new Message(target, packet.messageBody(), packet.messageNumber(), Status.SENT, true, now));
		return everyone ? logged : logged.numberUsed();
	}

	/** True if a module with its beacon on is due to beacon at {@code now}, {@code intervalTicks} after the last. */
	public static boolean beaconDue(HandheldAprs aprs, long now, long intervalTicks) {
		return aprs.beacon() && (aprs.lastBeacon() < 0 || now - aprs.lastBeacon() >= intervalTicks || now < aprs.lastBeacon());
	}

	/** A beacon of {@code myCall} at {@code pos}, with the module's icon and comment; the module notes when it went. */
	public static HandheldAprs beacon(HandheldAprs aprs, String myCall, BlockPos pos, long now, List<TncPacket> out, LongSupplier ids) {
		if(myCall.isEmpty())
			return aprs;
		out.add(TncPacket.beacon(ids.getAsLong(), myCall, pos, aprs.settings().comment(), aprs.settings().icon()));
		return aprs.withLastBeacon(now);
	}
}
