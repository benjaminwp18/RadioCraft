package com.arrl.radiocraft.common.radio.voice.handheld;

import com.arrl.radiocraft.api.antenna.IAntenna;
import com.arrl.radiocraft.api.antenna.IAntennaPacket;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.BandUtils;
import com.arrl.radiocraft.common.radio.antenna.AntennaDataPacket;
import com.arrl.radiocraft.common.radio.antenna.AntennaNetwork;
import com.arrl.radiocraft.common.radio.antenna.AntennaVoicePacket;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.solar.Aurora;
import com.arrl.radiocraft.common.radio.solar.SporadicE;
import com.arrl.radiocraft.common.radio.terrain.TerrainPropagation;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * How a handheld's rubber duck puts voice and packets on the air, wherever the handheld is (in a hand, in a desk
 * charger): line of sight through hills and roofs, aurora and sporadic E on VHF, and no skip.
 */
public final class HandheldRadiation {

	private HandheldRadiation() {}

	/** Sends voice (or tones) from {@code self} to every antenna on {@code network} in reach. */
	public static void voice(IAntenna self, AntennaNetwork network, de.maxhenkel.voicechat.api.ServerLevel level, short[] rawAudio, Band band, float frequencyHertz, UUID sourcePlayer, float ctcssTone) {
		IAntenna.AntennaPos here = self.getAntennaPos();
		com.arrl.radiocraft.common.radio.TransmitterLog.record(self, here, band, frequencyHertz); // On the air: direction finders can hear it.
		if(network == null || here == null || here.level() == null || !here.level().equals(level.getServerLevel()))
			return;
		com.arrl.radiocraft.common.moderation.ModerationHooks.voice(self, self, here.level(), sourcePlayer, rawAudio, frequencyHertz, band, ctcssTone); // Logged once, at the source.
		for(IAntenna antenna : others(network)) {
			IAntenna.AntennaPos there = antenna.getAntennaPos();
			if(antenna == self || there == null || there.level() != here.level() || BandUtils.outOfReach(band, here.world(), there.world()))
				continue;
			AntennaVoicePacket packet = new AntennaVoicePacket(level, rawAudio.clone(), band, frequencyHertz, 1.0F, self, sourcePlayer, ctcssTone);
			packet.setDestination(antenna);
			packet.setStrength(strength(packet, (ServerLevel)here.level(), band, here, there));
			antenna.receiveAudioPacket(packet);
		}
	}

	/** Sends a packet from {@code self} to every antenna on {@code network} in reach, at the strength its voice would have. */
	public static void data(IAntenna self, AntennaNetwork network, ServerLevel level, TncPacket payload, Band band, float frequencyHertz) {
		IAntenna.AntennaPos here = self.getAntennaPos();
		if(network == null || here == null || here.level() != level)
			return;
		com.arrl.radiocraft.common.radio.TransmitterLog.record(self, here, band, frequencyHertz);
		com.arrl.radiocraft.common.radio.voice.RadioModeration.data(level, payload, frequencyHertz);
		net.minecraft.world.entity.player.Player holder = self.getPlayer();
		com.arrl.radiocraft.common.moderation.ModerationHooks.data(self, self, level, payload, frequencyHertz, band, holder == null ? null : holder.getUUID());
		for(IAntenna antenna : others(network)) {
			IAntenna.AntennaPos there = antenna.getAntennaPos();
			if(antenna == self || there == null || there.level() != here.level() || BandUtils.outOfReach(band, here.world(), there.world()))
				continue;
			AntennaDataPacket packet = new AntennaDataPacket(level, payload, band, frequencyHertz, 1.0D, self);
			packet.setDestination(antenna);
			packet.setStrength(strength(packet, level, band, here, there));
			if(packet.getStrength() > 0.0D)
				antenna.receiveDataPacket(packet);
		}
	}

	/** A copy of the network's antennas: iterating its synchronized set while repeaters send would deadlock. */
	private static Set<IAntenna> others(AntennaNetwork network) {
		Set<IAntenna> all = network.allAntennas();
		synchronized(all) {
			return new HashSet<>(all);
		}
	}

	private static double strength(IAntennaPacket packet, ServerLevel level, Band band, IAntenna.AntennaPos here, IAntenna.AntennaPos there) {
		double distance = Math.sqrt(here.world().distSqr(there.world()));
		double lineOfSight = BandUtils.getBaseStrength(band, distance, 1.0F, 0.0F, level.isDay());
		if(lineOfSight > 0.0D) // A handheld has no skip: only hills and roofs in the way.
			lineOfSight *= TerrainPropagation.factor(packet, there.position(), false);
		return Math.max(lineOfSight, Math.max(Aurora.vhfStrength(level, band, here.position(), there.position(), distance), SporadicE.vhfStrength(level, band, distance)));
	}
}
