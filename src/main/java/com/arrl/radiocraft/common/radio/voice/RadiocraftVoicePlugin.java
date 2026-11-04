package com.arrl.radiocraft.common.radio.voice;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.blockentities.radio.IVoiceTransmitter;
import com.arrl.radiocraft.common.radio.VoiceTransmitters;
import com.arrl.radiocraft.common.radio.voice.EncodingManager.EncodingData;
import com.arrl.radiocraft.common.radio.voice.handheld.PlayerRadioManager;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

@ForgeVoicechatPlugin
public class RadiocraftVoicePlugin implements VoicechatPlugin {

	public static VoicechatServerApi API = null;

	/**
	 * Moves {@code channel}, a block's speaker, to where the block at {@code pos} really is when it rides a Sable ship;
	 * leaves it be otherwise. Call before sending audio. Game or voice thread.
	 */
	public static void followBlock(@org.jetbrains.annotations.Nullable de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel channel, @org.jetbrains.annotations.Nullable Level level, net.minecraft.core.BlockPos pos) {
		if(channel == null || API == null || !com.arrl.radiocraft.compat.sable.Sublevels.onSublevel(level, pos))
			return;
		Vec3 at = com.arrl.radiocraft.compat.sable.Sublevels.worldCenter(level, pos);
		channel.updateLocation(API.createPosition(at.x, at.y, at.z));
	}
	public static EncodingManager encodingManager = new EncodingManager();

	//TODO not currently working, will be used by audiochannels such as those in PlayerRadio
//	public static VolumeCategory handheldRadiosVolumeCategory;

	@Override
	public String getPluginId() {
		return Radiocraft.MOD_ID;
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket, 0);
		registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected, 0);
		registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStart, 0);
	}

	public void onServerStart(VoicechatServerStartedEvent event){
		API = event.getVoicechat(); // Available from server start, not only after the first microphone packet.
		//TODO not currently working
//		handheldRadiosVolumeCategory = event.getVoicechat().volumeCategoryBuilder()
//				.setId("radiocraft_radios")
//				.setName("Radios")
//				.setDescription("Volume for Radios")
//				.build();
//		event.getVoicechat().registerVolumeCategory(handheldRadiosVolumeCategory);
	}

	private static volatile long pttDebugLastLog = 0L; // Rate limit for the [ptt-debug] voice line.

	public void onMicrophonePacket(MicrophonePacketEvent event) { // This should only be called serverside
		if(API == null)
			API = event.getVoicechat();

		VoicechatConnection connection = event.getSenderConnection();

		if(connection == null) return; //the voicechat api is written such that the server could technically generate microphone packets (not specific to a player), but they wouldn't have a location so we ignore. Could implement something in the future

		de.maxhenkel.voicechat.api.ServerPlayer sender = connection.getPlayer();

		if(sender.getPlayer() instanceof ServerPlayer player) {

			// Decode voice packet
			EncodingData encodingData = encodingManager.getOrCreate(sender.getUuid());
			byte[] encodedAudio = event.getPacket().getOpusEncodedData();
			short[] decodedAudio = encodingData.getDecoder().decode(encodedAudio);
			if(encodedAudio.length == 0)
				encodingData.reset();
			else {
				// The moderation log keeps this Opus packet as it arrived: plain voice chat if asked, and for any radio that sends it.
				com.arrl.radiocraft.common.moderation.ModerationHooks.proximity(sender.getUuid(), player.level(), sender.getPosition().getX(), sender.getPosition().getY(),
						sender.getPosition().getZ(), encodedAudio, event.getPacket().isWhispering());
				com.arrl.radiocraft.common.moderation.ModerationHooks.micBegin(sender.getUuid(), encodedAudio);
				try {
					handOut(sender, decodedAudio);
				}
				finally {
					com.arrl.radiocraft.common.moderation.ModerationHooks.micEnd();
				}
			}
		}
	}

	/** Hands a decoded microphone frame to the speaker's manpack, handhelds and every radio microphone in earshot. Voice chat thread. */
	private void handOut(de.maxhenkel.voicechat.api.ServerPlayer sender, short[] decodedAudio) {
		// The wearer's HF manpack, keyed by its PTT or handset, hears only its wearer.
		com.arrl.radiocraft.common.radio.voice.handheld.ManpackRadio.get(sender.getUuid())
				.ifPresent(manpack -> manpack.acceptVoicePacket(sender.getServerLevel(), decodedAudio, sender.getUuid()));
		PlayerRadioManager.get(sender.getUuid()).ifPresent(playerRadio -> {
			//still need to reconsider the structure for static antennas so not deleting this just yet, but for synchronization the check is moved inside
//					if (playerRadio.canTransmitVoice()){ //TODO reconsider this structure, should the onus be on the radio to know if it should transmit instead?
				playerRadio.acceptVoicePacket(sender.getServerLevel(), decodedAudio, sender.getUuid());
//					}
		});

		//this is the auditory range NOT the radio range
		double sqrRange = API.getBroadcastRange();
		sqrRange *= sqrRange;

		List<IVoiceTransmitter> listeningMics = VoiceTransmitters.LISTENERS.get((Level) sender.getServerLevel().getServerLevel());

		if(listeningMics != null) {

			//make a copy so we don't have to hold up the game thread
			List<IVoiceTransmitter> listeningMicsCopy;
			synchronized (listeningMics) {
				listeningMicsCopy = List.copyOf(listeningMics);
			}

			for (IVoiceTransmitter listener : listeningMicsCopy) { // All radio's microphones in audible range of the sender will receive the packet
				Vec3 pos = listener.getPos();
				Position playerPos = sender.getPosition();

				if (listener instanceof com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity pttRadio && pttRadio.isPTTDown()
						&& System.currentTimeMillis() - pttDebugLastLog > 1000L) { // Once a second, whether a keyed radio gets the voice (logs/debug.log).
					pttDebugLastLog = System.currentTimeMillis();
					com.arrl.radiocraft.Radiocraft.LOGGER.debug("[ptt-debug] voice for keyed radio {} micPos={} dist={} range={} canTransmitVoice={}", pttRadio.getBlockPos().toShortString(), pos,
							Math.sqrt(pos.distanceToSqr(new Vec3(playerPos.getX(), playerPos.getY(), playerPos.getZ()))), Math.sqrt(sqrRange), listener.canTransmitVoice());
				}

				if (pos.distanceToSqr(new Vec3(playerPos.getX(), playerPos.getY(), playerPos.getZ())) > sqrRange)
					continue; // Do not transmit if out of range.

				if (!listener.canTransmitVoice())
					continue; // Do not transmit if listener is not accepting packets.

				listener.acceptVoicePacket(sender.getServerLevel(), decodedAudio, sender.getUuid(), new Vec3(playerPos.getX(), playerPos.getY(), playerPos.getZ()));
			}
		}
	}

	public void onPlayerDisconnected(PlayerDisconnectedEvent event) {
		encodingManager.close(event.getPlayerUuid());
	}

}
