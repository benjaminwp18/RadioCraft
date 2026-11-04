package com.arrl.radiocraft.common.radio.voice.handheld;

import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.api.antenna.IAntenna;
import com.arrl.radiocraft.api.antenna.IAntennaType;
import com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability;
import com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.antenna.AntennaCWPacket;
import com.arrl.radiocraft.common.radio.antenna.AntennaDataPacket;
import com.arrl.radiocraft.common.radio.antenna.AntennaNetwork;
import com.arrl.radiocraft.common.radio.antenna.AntennaVoicePacket;
import com.arrl.radiocraft.common.radio.antenna.data.AntennaData;
import com.arrl.radiocraft.common.radio.antenna.data.RubberDuckyAntennaData;
import com.arrl.radiocraft.common.radio.antenna.networks.AntennaNetworkManager;
import com.arrl.radiocraft.common.radio.antenna.types.RubberDuckyAntennaType;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.morse.CWBuffer;
import com.arrl.radiocraft.common.radio.voice.Afsk;
import com.arrl.radiocraft.common.radio.voice.Ctcss;
import com.arrl.radiocraft.common.radio.voice.Detuning;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin;
import com.arrl.radiocraft.common.radio.voice.SpeakerKind;
import com.arrl.radiocraft.common.radio.voice.SquelchTail;
import com.arrl.radiocraft.common.radio.voice.VoiceMix;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A handheld standing in a desk charger, still a radio: switched on, it hears its frequency through its speaker at the
 * charger (tone squelch and the APRS mute as in the hand), and its APRS module keeps beaconing (from the charger) and
 * taking packets, under the callsign it last had in its holder's hands. It cannot be keyed for voice there. The
 * charger owns one while it holds a handheld, ticks it and lets it go when the handheld leaves or the block unloads.
 */
public class DockedHandheld implements IAntenna, HandheldTnc.Host {

	/** What the voice thread needs to know of the handheld, renewed every tick (null while it is off). */
	private record State(float frequency, float rxTone, float gain, boolean muted) {}

	private final ServerLevel level;
	private final BlockPos pos;
	private final AtomicReference<AntennaPos> antennaPos = new AtomicReference<>();
	private final HandheldTnc tnc;
	private final AntennaNetwork network = AntennaNetworkManager.getNetwork(AntennaNetworkManager.VHF_ID);
	private final Runnable onChanged;

	private ItemStack stack = ItemStack.EMPTY; // Server thread.
	private volatile State state = null;
	private volatile long lastHeardNanos = Long.MIN_VALUE / 2;
	private volatile boolean indoors = false; // No sky above the charger; checked on the server thread.

	// Voice thread (under this object's lock).
	private final VoiceMix.Aligner aligner = new VoiceMix.Aligner();
	private final VoiceMix.Renderer renderer = new VoiceMix.Renderer();
	private final SquelchTail squelchTail = new SquelchTail();
	private LocationalAudioChannel channel = null;
	private de.maxhenkel.voicechat.api.opus.OpusEncoder encoder = null;

	/** A docked handheld at {@code pos}; {@code onChanged} runs when its item changes (to send it to clients). */
	public DockedHandheld(ServerLevel level, BlockPos pos, Runnable onChanged) {
		this.level = level;
		this.pos = pos.immutable();
		this.onChanged = onChanged;
		this.antennaPos.set(new AntennaPos(this.pos, level));
		this.tnc = new HandheldTnc(this, "dock-" + level.dimension().location() + "-" + this.pos.asLong());
		network.addAntenna(this);
	}

	/** Takes the handheld in the charger this tick (the live stack), beacons and sends bursts, plays the squelch tail. */
	public void tick(ItemStack handheld) {
		stack = handheld;
		if(level.getGameTime() % 20L == 0L)
			indoors = com.arrl.radiocraft.common.radio.terrain.TerrainPropagation.isIndoors(level, pos.above());
		IVHFHandheldCapability cap = handheld.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
		if(cap == null || !cap.isPowered()) {
			state = null;
			return;
		}
		HandheldAprs aprs = handheld.get(RadiocraftDataComponent.HANDHELD_APRS.value());
		state = new State(cap.getFrequencyHertz(), cap.getRxTone(), cap.getGain(), aprs != null && aprs.mute());
		tnc.tick(level);
		tickSquelchTail();
	}

	/** True if it heard something in the last few frames (its screen lights). */
	public boolean isReceiving() {
		return state != null && System.nanoTime() - lastHeardNanos < 250_000_000L;
	}

	/** Leaves the air: the charger lost the handheld or was unloaded. */
	public synchronized void close() {
		network.removeAntenna(this);
		state = null;
		if(encoder != null) {
			encoder.close();
			encoder = null;
		}
	}

	// -------------------- RECEIVE --------------------

	@Override
	public void receiveAudioPacket(AntennaVoicePacket packet) {
		State now = state;
		if(now == null || tnc.isSending() || packet.getLevel() != level || RadiocraftVoicePlugin.API == null)
			return;
		if(now.muted() && Afsk.isDataSource(packet.getSourcePlayer()))
			return;
		Detuning.Effect effect = Detuning.fm(now.frequency(), packet.getFrequency(), RadiocraftServerConfig.VHF_FREQUENCY_STEP.get());
		if(!effect.audible() || !Ctcss.opens(now.rxTone(), packet.getCtcssTone()))
			return;
		double strength = packet.getStrength() * effect.strength();
		if(strength <= 0.0D)
			return;
		com.arrl.radiocraft.common.moderation.ModerationHooks.heard(packet, this, level, pos, null, strength); // Its speaker: players near it are found by the log.
		AntennaPos source = packet.getSource() == null ? null : packet.getSource().getAntennaPos();
		boolean beyondLos = source != null && packet.getBand() != null
				&& source.world().distSqr(pos) > (double)packet.getBand().losRange() * packet.getBand().losRange();
		synchronized(this) {
			if(!openChannel())
				return;
			long nanos = System.nanoTime();
			lastHeardNanos = nanos;
			List<VoiceMix.Signal> slot = aligner.offer(new VoiceMix.Signal(VoiceMix.key(packet.getSourcePlayer(), packet.getSource()), packet.getSourcePlayer(),
					packet.getRawAudio(), 0.5D * PlayerRadio.speakerLevel(now.gain()), beyondLos ? 1.0D : 0.0D, 0.0D, 0.0D, 0.0D, strength), nanos);
			if(slot.isEmpty())
				return; // Another station leads: this frame goes out mixed with its next one.
			VoiceMix.Mixed mixed = renderer.render(slot, SpeakerKind.BUILT_IN.apply(RadioVoiceProcessor.currentSettings()), 1.0D, nanos);
			channel.send(encoder().encode(mixed.pcm()));
			squelchTail.heard(mixed.loudest().fmSignal(), mixed.loudest().level(), mixed.loudest().source(), nanos);
		}
	}

	/** The speaker at the charger, heard 12 blocks round. */
	private boolean openChannel() {
		if(channel == null) {
			channel = RadiocraftVoicePlugin.API.createLocationalAudioChannel(UUID.randomUUID(), RadiocraftVoicePlugin.API.fromServerLevel(level),
					RadiocraftVoicePlugin.API.createPosition(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D));
			if(channel != null)
				channel.setDistance(12.0F);
		}
		return channel != null;
	}

	private de.maxhenkel.voicechat.api.opus.OpusEncoder encoder() {
		if(encoder == null)
			encoder = RadiocraftVoicePlugin.API.createEncoder();
		return encoder;
	}

	private synchronized void tickSquelchTail() {
		if(channel == null || RadiocraftVoicePlugin.API == null)
			return;
		for(short[] frame : squelchTail.drain(System.nanoTime(), level.getGameTime() % 2 == 0 ? 3 : 2))
			channel.send(encoder().encode(frame));
	}

	@Override
	public void receiveDataPacket(AntennaDataPacket packet) {
		if(state != null && tnc.receive(packet, level))
			com.arrl.radiocraft.common.moderation.ModerationHooks.heard(packet, this, level, pos, null, packet.getStrength());
	}

	@Override
	public void receiveCWPacket(AntennaCWPacket packet) {}

	// -------------------- TRANSMIT (APRS ONLY) --------------------

	@Override
	public void transmitAudioPacket(de.maxhenkel.voicechat.api.ServerLevel svcLevel, short[] rawAudio, Band band, float frequencyHertz, UUID sourcePlayer) {
		transmitAudioPacket(svcLevel, rawAudio, band, frequencyHertz, sourcePlayer, 0.0F);
	}

	@Override
	public void transmitAudioPacket(de.maxhenkel.voicechat.api.ServerLevel svcLevel, short[] rawAudio, Band band, float frequencyHertz, UUID sourcePlayer, float ctcssTone) {
		HandheldRadiation.voice(this, network, svcLevel, rawAudio, band, frequencyHertz, sourcePlayer, ctcssTone);
	}

	@Override
	public void transmitDataPacket(ServerLevel serverLevel, TncPacket payload, Band band, float frequencyHertz) {
		HandheldRadiation.data(this, network, serverLevel, payload, band, frequencyHertz);
	}

	@Override
	public void transmitCWPacket(ServerLevel serverLevel, Collection<CWBuffer> buffers, Band band, float frequencyHertz) {}

	// -------------------- THE MODEM'S HOST --------------------

	@Override
	public List<HandheldTnc.Module> modules() {
		HandheldAprs aprs = stack.get(RadiocraftDataComponent.HANDHELD_APRS.value());
		return state == null || aprs == null ? List.of() : List.of(new HandheldTnc.Module(stack, aprs, true));
	}

	@Override
	public String callsign() {
		HandheldAprs aprs = stack.get(RadiocraftDataComponent.HANDHELD_APRS.value());
		return aprs == null ? "" : aprs.callsign(); // Its holder's, as it last saw it.
	}

	@Override
	public BlockPos position() {
		return com.arrl.radiocraft.compat.sable.Sublevels.world(level, pos);
	}

	@Override
	public void burst(ServerLevel serverLevel, short[] frame, float frequency, UUID voiceId) {
		transmitAudioPacket(() -> serverLevel, frame, HandheldTnc.band(), frequency, voiceId, 0.0F);
	}

	@Override
	public void radiate(ServerLevel serverLevel, TncPacket packet, float frequency) {
		transmitDataPacket(serverLevel, packet, HandheldTnc.band(), frequency);
	}

	@Override
	public void changed() {
		onChanged.run();
	}

	// -------------------- ANTENNA --------------------

	@Override
	public AntennaPos getAntennaPos() {
		return antennaPos.get();
	}

	@Override
	public IAntennaType<? extends AntennaData> getType() {
		return new RubberDuckyAntennaType();
	}

	@Override
	public AntennaData getData() {
		return new RubberDuckyAntennaData(0.155);
	}

	@Override
	@Nullable
	public Player getPlayer() {
		return null;
	}

	@Override
	public boolean isIndoors() {
		return indoors;
	}
}
