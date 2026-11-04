package com.arrl.radiocraft.common.radio.voice.handheld;

import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.antenna.AntennaDataPacket;
import com.arrl.radiocraft.common.radio.data.HandheldAprsStation;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.voice.Afsk;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The modem of the APRS modules in one place's handhelds (a player's, or one in a desk charger): it beacons, sends
 * messages and acks as AFSK bursts on the APRS frequency, and hands the packets it decodes to the modules, which keep
 * what they heard on their items. Server thread only.
 */
public final class HandheldTnc {

	/** A handheld with an APRS module: its item, the module, and whether it is listening (on and not keyed up). */
	public record Module(ItemStack item, HandheldAprs aprs, boolean listening) {}

	/** Where the handhelds are and how their transmitter reaches the air. */
	public interface Host {
		/** The switched-on handhelds with a module. */
		List<Module> modules();

		/** The callsign the modules send under (without SSID), empty for none. */
		String callsign();

		BlockPos position();

		/** One 20 ms frame of a burst, on {@code frequency}, under the voice id {@code voiceId}. */
		void burst(ServerLevel level, short[] frame, float frequency, UUID voiceId);

		/** The packet itself, once its burst is over. */
		void radiate(ServerLevel level, TncPacket packet, float frequency);

		/** A message for this station came in. */
		default void messageArrived() {}

		/** A module's item changed (to send it to watching clients). */
		default void changed() {}
	}

	/** Packet ids taken lately: digipeaters repeat each one, and it is kept once. */
	private static final int SEEN_PACKETS = 64;
	/** Most packets queued at once; more are dropped. */
	private static final int QUEUE_MAX = 8;

	private final Host host;
	private final UUID voiceId;
	private final ArrayDeque<TncPacket> queue = new ArrayDeque<>();
	private final LinkedHashSet<Long> seen = new LinkedHashSet<>();
	private TncPacket sending = null;
	private List<short[]> frames = List.of();
	private int frame = 0;
	private volatile boolean busy = false;

	/** A modem for {@code host}; {@code name} makes its bursts' voice id (one per player or per charger). */
	public HandheldTnc(Host host, String name) {
		this.host = host;
		this.voiceId = Afsk.markDataSource(UUID.nameUUIDFromBytes(("radiocraft-handheld-tnc-" + name).getBytes(StandardCharsets.UTF_8)));
	}

	/** The frequency the APRS module works on, its own receiver whatever the handheld is tuned to. */
	public static float frequency() {
		return (float)(RadiocraftServerConfig.HANDHELD_APRS_FREQUENCY_MHZ.get() * 1_000_000.0D);
	}

	/** True while a burst is going out or waiting to: the transmitter is keyed and the receivers are deaf. Any thread. */
	public boolean isSending() {
		return busy;
	}

	private void queue(List<TncPacket> packets) {
		for(TncPacket packet : packets)
			if(queue.size() < QUEUE_MAX)
				queue.add(packet);
	}

	/** Sends {@code text} to {@code to} ("ALL" for everyone) from the handheld {@code stack}, and logs it there. */
	public void sendMessage(ItemStack stack, String to, String text, ServerLevel level) {
		HandheldAprs aprs = stack.get(RadiocraftDataComponent.HANDHELD_APRS.value());
		String myCall = aprs == null ? "" : HandheldAprs.withSsid(host.callsign(), aprs.settings().ssid());
		if(aprs == null || myCall.isEmpty())
			return;
		List<TncPacket> out = new ArrayList<>();
		stack.set(RadiocraftDataComponent.HANDHELD_APRS.value(), HandheldAprsStation.send(aprs, myCall, to, text, level.getGameTime(), out, () -> level.getRandom().nextLong()));
		queue(out);
		host.changed();
	}

	/** Beacons from any module whose timer is up (one beacon covers them all), then plays the burst in hand. */
	public void tick(ServerLevel level) {
		long now = level.getGameTime();
		long shortest = RadiocraftServerConfig.HANDHELD_APRS_BEACON_SECONDS.get() * 20L;
		List<Module> modules = host.modules();
		boolean sent = false;
		for(Module module : modules) {
			if(!HandheldAprsStation.beaconDue(module.aprs(), now, Math.max(shortest, module.aprs().settings().interval() * 20L)))
				continue;
			String myCall = HandheldAprs.withSsid(host.callsign(), module.aprs().settings().ssid());
			if(myCall.isEmpty())
				break;
			List<TncPacket> out = new ArrayList<>();
			module.item().set(RadiocraftDataComponent.HANDHELD_APRS.value(), HandheldAprsStation.beacon(module.aprs(), myCall, host.position(), now, out, () -> level.getRandom().nextLong()));
			if(!sent)
				queue(out);
			sent = true;
			host.changed();
		}
		tickBurst(level, !modules.isEmpty());
	}

	/** Plays the burst for the packet in hand, 2.5 frames of 20 ms a tick, and puts the packet on the air once it is done. */
	private void tickBurst(ServerLevel level, boolean on) {
		if(sending == null) {
			sending = on ? queue.poll() : null;
			if(sending == null) {
				queue.clear();
				busy = false;
				return;
			}
			frames = Afsk.burst(sending.airBytes(), sending.id());
			frame = 0;
			busy = true;
		}
		int count = level.getGameTime() % 2 == 0 ? 3 : 2;
		for(int i = 0; i < count && frame < frames.size(); i++)
			host.burst(level, frames.get(frame++), frequency(), voiceId);
		if(frame >= frames.size()) {
			seen.add(sending.id());
			host.radiate(level, sending, frequency());
			sending = null;
		}
	}

	/** Band the module works on. */
	public static Band band() {
		return Band.getBand("2m");
	}

	/**
	 * A packet heard: if it is on the APRS frequency and strong enough, every module listening takes it as this
	 * station, and an ack goes back for a message. False if no module took it.
	 */
	public boolean receive(AntennaDataPacket packet, ServerLevel level) {
		if(busy || Math.abs(packet.getFrequency() - frequency()) > 1000.0F)
			return false;
		if(!com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity.decodes(packet.getStrength(), RadiocraftServerConfig.VHF_NOISE_FLOOR.get()))
			return false;
		TncPacket payload = packet.getPayload();
		if(seen.contains(payload.id()))
			return false;
		long now = level.getGameTime();
		boolean replied = false;
		boolean taken = false;
		boolean newMessage = false;
		for(Module module : host.modules()) {
			if(!module.listening())
				continue;
			taken = true;
			HandheldAprs before = module.aprs();
			List<TncPacket> out = new ArrayList<>();
			HandheldAprs after = HandheldAprsStation.receive(before, payload, HandheldAprs.withSsid(host.callsign(), before.settings().ssid()), now, out, () -> level.getRandom().nextLong());
			if(after != before) {
				HandheldAprs.Message top = after.messages().isEmpty() ? null : after.messages().getFirst();
				newMessage |= top != null && top.incoming() && (before.messages().isEmpty() || top != before.messages().getFirst());
				module.item().set(RadiocraftDataComponent.HANDHELD_APRS.value(), after);
				host.changed();
			}
			if(!replied)
				queue(out); // One ack is enough, however many modules heard it.
			replied |= !out.isEmpty();
		}
		if(!taken)
			return false;
		seen.add(payload.id());
		while(seen.size() > SEEN_PACKETS)
			seen.remove(seen.iterator().next());
		if(newMessage)
			host.messageArrived();
		return true;
	}
}
