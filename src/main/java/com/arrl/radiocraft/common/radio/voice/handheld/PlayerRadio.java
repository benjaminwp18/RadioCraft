package com.arrl.radiocraft.common.radio.voice.handheld;

import com.arrl.radiocraft.common.items.VHFHandheldItem;
import com.arrl.radiocraft.common.radio.crystal.CrystalQuality;
import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.api.antenna.IAntenna;
import com.arrl.radiocraft.api.antenna.IAntennaType;
import com.arrl.radiocraft.api.blockentities.radio.IVoiceTransmitter;
import com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability;
import com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.BandUtils;
import com.arrl.radiocraft.common.radio.IVoiceReceiver;
import com.arrl.radiocraft.common.radio.antenna.AntennaCWPacket;
import com.arrl.radiocraft.common.radio.antenna.AntennaDataPacket;
import com.arrl.radiocraft.common.radio.data.HandheldAprsStation;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.radio.antenna.data.AntennaData;
import com.arrl.radiocraft.common.radio.antenna.AntennaNetwork;
import com.arrl.radiocraft.common.radio.antenna.AntennaVoicePacket;
import com.arrl.radiocraft.common.radio.antenna.networks.AntennaNetworkManager;
import com.arrl.radiocraft.common.radio.antenna.types.RubberDuckyAntennaType;
import com.arrl.radiocraft.common.radio.antenna.data.RubberDuckyAntennaData;
import com.arrl.radiocraft.common.radio.morse.CWBuffer;
import com.arrl.radiocraft.common.radio.solar.Aurora;
import com.arrl.radiocraft.common.radio.solar.SporadicE;
import com.arrl.radiocraft.common.radio.terrain.TerrainPropagation;
import com.arrl.radiocraft.compat.curios.CuriosCompat;
import com.arrl.radiocraft.common.radio.voice.Ctcss;
import com.arrl.radiocraft.common.radio.voice.Detuning;
import com.arrl.radiocraft.common.radio.voice.RadioVoiceProcessor;
import com.arrl.radiocraft.common.radio.voice.ReceiverMix;
import com.arrl.radiocraft.common.radio.voice.RadiocraftVoicePlugin;
import de.maxhenkel.voicechat.api.ServerLevel;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link PlayerRadio} acts as a container used for the transmission and receiving of signals for VHF handsets.
 */
public class PlayerRadio implements IVoiceTransmitter, IVoiceReceiver, IAntenna {

    private EntityAudioChannel receiveChannel = null;
    // Stations heard at once are lined up and mixed (FM capture); under this object's lock (receive is synchronized).
    private final com.arrl.radiocraft.common.radio.voice.VoiceMix.Aligner aligner = new com.arrl.radiocraft.common.radio.voice.VoiceMix.Aligner();
    private final com.arrl.radiocraft.common.radio.voice.VoiceMix.Renderer renderer = new com.arrl.radiocraft.common.radio.voice.VoiceMix.Renderer();
    private de.maxhenkel.voicechat.api.opus.OpusEncoder encoder; // The handheld's own, not the talker's (shared by every radio).
    private WeakReference<Player> playerRef = null; // Use a weak ref here, so it isn't able to permanently load the entity.
    private volatile AntennaNetwork network = null;

    private volatile List<SynchronousRadioState> radios = Collections.emptyList();
    //fun fact, all updates to volatile references are atomic inherently! AtomicReference is used here for readability
    //and to ensure programmers understand they don't need to synchronize on PlayerRadio to access this
    private final AtomicReference<IAntenna.AntennaPos> antennaPos = new AtomicReference<>();
    //position and level for voiceChannel;
    private volatile Vec3 voicePosition;
    private volatile Level voiceLevel;

    private boolean isUseHeld;
    private volatile boolean wornPttHeld; // The "talk on worn radio" key, for a handheld worn in a Curios slot.
    private volatile boolean indoors; // No sky above the player's head, updated each tick on the server thread.

    public void setUseHeld(boolean useHeld) {
        this.isUseHeld = useHeld;
    }

    public void setWornPttHeld(boolean held) {
        this.wornPttHeld = held;
    }

    /** True while the player holds the use key (right click), as their client last reported. */
    public boolean isUseHeld() {
        return isUseHeld;
    }

    @SuppressWarnings("this-escape")
    public PlayerRadio(Player player) {
        tnc = new HandheldTnc(new TncHost(), player == null ? "nobody" : player.getUUID().toString());
        setPlayer(player);
    }

    @Override
    public boolean isIndoors() {
        return indoors;
    }

    public Player getPlayer() {
        return playerRef == null ? null : playerRef.get();
    }

    /**
     * Set the player reference for this object and update it's {@link AntennaNetwork}. If player is null, this radio
     * will be disconnected from all networks.
     * @param player The player to target.
     */
    public void setPlayer(Player player) {
        synchronized (this) {
            playerRef = new WeakReference<>(player);
            this.tick();
        }

        if(player != null)
            setNetwork(AntennaNetworkManager.getNetwork(AntennaNetworkManager.VHF_ID)); // Always swap to a new network in case it was a dimension change.
        else if(network != null) {
            network.removeAntenna(this);
            network = null;
        }
    }

    protected enum HandheldLocation{
        HELD,
        HOT_BAR,
        BACKPACK
    }

    /**
     * Ephemeral state for each handheld for each tick.
     * Each tick each radio gets a new SynchronousRadioState instance
     * that only lives until the next tick
     */
    protected static class SynchronousRadioState{

        final ItemStack item;

        final HandheldLocation itemLocation;

        final boolean canReceive;
        final boolean canTransmit;

        final float frequency; // Where the radio really is: its dial frequency plus any crystal drift.
        final CrystalQuality crystal;

        final float gain;
        final float micGain;

        final float ctcssTone; // Sent under the voice (TX tone); 0 for none.
        final float rxTone; // Needed to open the squelch (RX tone); 0 for none.
        final int offsetHz; // Repeater offset: transmits this far from frequency.
        final com.arrl.radiocraft.common.datacomponents.HandheldAprs aprs; // The APRS module, null without one.

        //used for calculating average amplitude for power meter
        volatile long runningSampleSum=0; //stores the sum of the square of every sample since last tick
        volatile long runningSampleCount=0; //number of samples since last tick

        public SynchronousRadioState(ItemStack item, boolean canReceive, boolean canTransmit, float frequency, HandheldLocation itemLocation, float gain, float micGain){
            this(item, canReceive, canTransmit, frequency, itemLocation, gain, micGain, 0.0f, 0, 0.0f);
        }

        public SynchronousRadioState(ItemStack item, boolean canReceive, boolean canTransmit, float frequency, HandheldLocation itemLocation, float gain, float micGain, float ctcssTone, int offsetHz, float rxTone){
            this.ctcssTone = ctcssTone;
            this.rxTone = rxTone;
            this.offsetHz = offsetHz;
            this.item = item;
            this.aprs = item.get(com.arrl.radiocraft.common.init.RadiocraftDataComponent.HANDHELD_APRS.value());
            this.canReceive = canReceive && !canTransmit; // Keyed up, the radio hears nothing (it is not a repeater).
            this.canTransmit = canTransmit;
            this.frequency = frequency;
            this.crystal = VHFHandheldItem.crystalOf(item);
            this.itemLocation = itemLocation;
            this.gain = gain;
            this.micGain = micGain;
        }

        /** True if the radio is switched on (whether it is receiving or sending). */
        boolean powered() {
            return canReceive || canTransmit;
        }

        /** True if APRS bursts stay out of this radio's speaker (its module is muted). */
        boolean aprsMuted() {
            return aprs != null && aprs.mute();
        }

        public SynchronousRadioState(ItemStack item, IVHFHandheldCapability cap, HandheldLocation itemLocation, float frequency) {
            this(item, cap.isPowered(), cap.isPowered() && cap.isPTTDown(), frequency, itemLocation, cap.getGain(), cap.getMicGain(), cap.getCtcssTone(), cap.getOffsetHz(), cap.getRxTone());
        }

        /** Where this radio transmits: its frequency plus the repeater offset, kept inside 2m. */
        float transmitFrequency() {
            Band band = Band.getBand("2m");
            float tx = frequency + offsetHz;
            return band == null ? tx : Math.max(band.minFrequency(), Math.min(band.maxFrequency(), tx));
        }
    }

    /** The handheld's dial frequency moved by its crystal's drift (poor crystals only); each slot drifts on its own. */
    private static float tunedFrequency(Player player, ItemStack stack, IVHFHandheldCapability cap, int slot) {
        long seed = player.getUUID().getLeastSignificantBits() + slot * 7_919L;
        return (float)(cap.getFrequencyHertz() + VHFHandheldItem.crystalOf(stack).driftHz(player.level().getGameTime(), seed));
    }

    protected List<SynchronousRadioState> genHandheldStates(Player player) {

        Inventory playerInventory = player.getInventory();
        LinkedList<SynchronousRadioState> out = new LinkedList<>();

        //Add offhand first, so it goes before other radios on the hot bar, but after the held item
        ItemStack offhand = player.getOffhandItem();
        if (offhand.getItem() instanceof VHFHandheldItem) {
            IVHFHandheldCapability cap = offhand.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
            if(cap != null) out.add(new SynchronousRadioState(offhand, cap, HandheldLocation.HELD, tunedFrequency(player, offhand, cap, -1)));
        }

        for(int i=0; i<playerInventory.getContainerSize(); i++) {
            ItemStack itemStack = player.getInventory().getItem(i);
            if(itemStack.getItem() instanceof VHFHandheldItem) {
                IVHFHandheldCapability cap = itemStack.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
                if(cap != null) {
                    //if the current radio is the held item, put it before the offhand and other radios
                    //otherwise add the radio to the end of the list, so the order is held, then offhand, then all others
                    if (i == playerInventory.selected) {
                        out.addFirst(new SynchronousRadioState(itemStack, cap.isPowered(), cap.isPowered() && (cap.isPTTDown() || this.isUseHeld), tunedFrequency(player, itemStack, cap, i), HandheldLocation.HELD, cap.getGain(), cap.getMicGain(), cap.getCtcssTone(), cap.getOffsetHz(), cap.getRxTone()));
                    } else {
                        out.addLast(new SynchronousRadioState(itemStack, cap, Inventory.isHotbarSlot(i) ? HandheldLocation.HOT_BAR : HandheldLocation.BACKPACK, tunedFrequency(player, itemStack, cap, i)));
                    }
                }
            }
        }

        // Worn on the belt (Curios): heard like one in the inventory, keyed with the "talk on worn radio" key.
        int worn = 0;
        for(ItemStack itemStack : CuriosCompat.wornHandhelds(player)) {
            IVHFHandheldCapability cap = itemStack.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
            if(cap != null)
                out.addLast(new SynchronousRadioState(itemStack, cap.isPowered(), cap.isPowered() && (cap.isPTTDown() || this.wornPttHeld),
                        tunedFrequency(player, itemStack, cap, 1000 + worn++), HandheldLocation.HOT_BAR, cap.getGain(), cap.getMicGain(), cap.getCtcssTone(), cap.getOffsetHz(), cap.getRxTone()));
        }
        return out;
    }

    @Override
    public Vec3 getPos() {
        return this.voicePosition;
    }

    @Override
    public AntennaPos getAntennaPos() {
        return this.antennaPos.get();
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
    public void transmitCWPacket(net.minecraft.server.level.ServerLevel level, Collection<CWBuffer> buffers, Band band, float frequencyHertz) {
        // Handheld doesn't have CW capability.
    }

    @Override
    public void receiveCWPacket(AntennaCWPacket packet) {
        // Handheld doesn't have CW capability.
    }

    @Override //TODO get rid of this
    public synchronized boolean isReceiving() {
        for(SynchronousRadioState state : radios) if(state.canReceive) return true;
        return false;
    }

    @Override
    public void setReceiving(boolean value) {

    }

    @Override
    public void transmitAudioPacket(ServerLevel level, short[] rawAudio, Band band, float frequencyHertz, UUID sourcePlayer) {
        transmitAudioPacket(level, rawAudio, band, frequencyHertz, sourcePlayer, 0.0F);
    }

    @Override
    public void transmitAudioPacket(ServerLevel level, short[] rawAudio, Band band, float frequencyHertz, UUID sourcePlayer, float ctcssTone) {
        AntennaNetwork network;
        synchronized (this) {
            network = this.network;
        }
        HandheldRadiation.voice(this, network, level, rawAudio, band, frequencyHertz, sourcePlayer, ctcssTone);
    }

    @Override //TODO get rid of this
    public synchronized boolean canTransmitVoice() {
        for(SynchronousRadioState state : radios) if(state.canTransmit) return true;
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean openChannel() {
        if(RadiocraftVoicePlugin.API == null)
            Radiocraft.LOGGER.error("VoiceChatServerApi is null, cannot open channel.");

        // You are supposed to use a _random_ uuid when making any audio channel, but there's a bug
        // where entity audio channels need to use the UUID of the entity they are attached to instead
        receiveChannel = RadiocraftVoicePlugin.API.createEntityAudioChannel(getPlayer().getUUID(), RadiocraftVoicePlugin.API.fromEntity(getPlayer()));
        if(receiveChannel == null) return false;
        receiveChannel.setDistance(16f);
//        receiveChannel.setCategory(RadiocraftVoicePlugin.handheldRadiosVolumeCategory.getId()); //TODO not currently working
//        receiveChannel = RadiocraftVoicePlugin.API.createEntityAudioChannel(UUID.randomUUID(), RadiocraftVoicePlugin.API.fromEntity(getPlayer()));
        return true;
    }

    // -------------------- DTMF --------------------

    /** Most keys queued at once (a whole code typed fast); keys past this are dropped. */
    private static final int DTMF_MAX_FRAMES = com.arrl.radiocraft.common.radio.voice.Dtmf.MAX_DIGITS * (com.arrl.radiocraft.common.radio.voice.Dtmf.TONE_FRAMES + com.arrl.radiocraft.common.radio.voice.Dtmf.GAP_FRAMES);
    private final ArrayDeque<short[]> dtmfFrames = new ArrayDeque<>(); // Server thread only.
    private float dtmfFrequency;
    private float dtmfCtcss;
    private volatile boolean sendingDtmf = false; // The mic is cut while the tones go out, as a real handheld does.

    /**
     * A key pressed on the DTMF page of the handheld in inventory slot {@code slot}: it keys up by itself and sends the
     * key's tones on its transmit frequency (repeater offset included) with its CTCSS tone. Server thread.
     */
    public void sendDtmf(int slot, char key) {
        Player player = getPlayer();
        if(player == null || !com.arrl.radiocraft.common.radio.voice.Dtmf.isKey(key) || dtmfFrames.size() >= DTMF_MAX_FRAMES)
            return;
        ItemStack stack = player.getInventory().getItem(slot);
        IVHFHandheldCapability cap = stack.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
        if(cap == null || !cap.isPowered())
            return;
        dtmfFrequency = new SynchronousRadioState(stack, cap, HandheldLocation.HELD, tunedFrequency(player, stack, cap, slot)).transmitFrequency();
        dtmfCtcss = cap.getCtcssTone();
        dtmfFrames.addAll(com.arrl.radiocraft.common.radio.voice.Dtmf.frames(key));
        sendingDtmf = true;
        com.arrl.radiocraft.common.moderation.ModerationHooks.dtmfQueued(this, String.valueOf(key));
    }

    /** True while DTMF tones are going out or waiting to. */
    public boolean isSendingDtmf() {
        return sendingDtmf;
    }

    /** Sends the queued DTMF frames, 2.5 per tick (20 ms each). */
    private void tickDtmf(Player player) {
        if(dtmfFrames.isEmpty()) {
            sendingDtmf = false;
            return;
        }
        if(!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel))
            return;
        int frames = serverLevel.getGameTime() % 2 == 0 ? 3 : 2;
        var was = com.arrl.radiocraft.common.moderation.ModerationHooks.push(com.arrl.radiocraft.common.moderation.ModerationHooks.Tag.dtmf(this));
        try {
            for(int i = 0; i < frames && !dtmfFrames.isEmpty(); i++)
                transmitAudioPacket(() -> serverLevel, dtmfFrames.poll(), Band.getBand("2m"), dtmfFrequency, player.getUUID(), dtmfCtcss);
        }
        finally {
            com.arrl.radiocraft.common.moderation.ModerationHooks.pop(was);
        }
    }

    // -------------------- APRS --------------------

    /** The modem of the APRS modules in the holder's handhelds (made in the constructor: its voice id is the player's). */
    private final HandheldTnc tnc;

    /** The holder's handhelds, as the modem sees them. */
    private final class TncHost implements HandheldTnc.Host {
        @Override
        public List<HandheldTnc.Module> modules() {
            List<HandheldTnc.Module> out = new ArrayList<>();
            for(SynchronousRadioState state : radios)
                if(state.aprs != null && state.powered())
                    out.add(new HandheldTnc.Module(state.item, state.aprs, state.canReceive));
            return out;
        }

        @Override
        public String callsign() {
            Player player = getPlayer();
            return player == null ? "" : callsignOf(player);
        }

        @Override
        public BlockPos position() {
            Player player = getPlayer();
            return player == null ? BlockPos.ZERO : player.blockPosition();
        }

        @Override
        public void burst(net.minecraft.server.level.ServerLevel level, short[] frame, float frequency, UUID voiceId) {
            transmitAudioPacket(() -> level, frame, HandheldTnc.band(), frequency, voiceId, 0.0F);
        }

        @Override
        public void radiate(net.minecraft.server.level.ServerLevel level, TncPacket packet, float frequency) {
            transmitDataPacket(level, packet, HandheldTnc.band(), frequency);
        }

        @Override
        public void messageArrived() {
            if(getPlayer() instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                serverPlayer.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.6F, 1.5F);
        }
    }

    /** The frequency the APRS module works on, its own receiver whatever the handheld is tuned to. */
    public static float aprsFrequency() {
        return HandheldTnc.frequency();
    }

    /** The holder's callsign as APRS sends it, empty if they have none. */
    private static String callsignOf(Player player) {
        if(player.getServer() == null)
            return "";
        String callsign = com.arrl.radiocraft.common.radio.Callsigns.playerCallsign(player.getServer(), player.getUUID());
        return callsign == null ? "" : TncPacket.sanitizeCallsign(callsign);
    }

    /**
     * The holder pressed SEND on the APRS page of the handheld in inventory slot {@code slot}: {@code text} goes to
     * {@code to} ("ALL" for everyone) and is logged on that handheld. Server thread.
     */
    public void sendAprsMessage(int slot, String to, String text) {
        Player player = getPlayer();
        if(player == null || !(player.level() instanceof net.minecraft.server.level.ServerLevel level))
            return;
        ItemStack stack = player.getInventory().getItem(slot);
        IVHFHandheldCapability cap = stack.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
        if(cap != null && cap.isPowered())
            tnc.sendMessage(stack, to, text, level);
    }

    /** Keeps the modules' copy of the holder's callsign current (their screens show it), beacons, and sends bursts. */
    private void tickAprs(Player player) {
        if(!(player.level() instanceof net.minecraft.server.level.ServerLevel level))
            return;
        if(level.getGameTime() % 20L == 0L) {
            String call = callsignOf(player);
            for(SynchronousRadioState state : radios)
                if(state.aprs != null && !state.aprs.callsign().equals(call))
                    state.item.set(RadiocraftDataComponent.HANDHELD_APRS.value(), state.aprs.withCallsign(call));
        }
        if(!sendingDtmf)
            tnc.tick(level);
    }

    /** Sends a packet from the APRS module to every antenna in reach, at the strength the handheld's voice would have. */
    @Override
    public void transmitDataPacket(net.minecraft.server.level.ServerLevel level, TncPacket payload, Band band, float frequencyHertz) {
        HandheldRadiation.data(this, this.network, level, payload, band, frequencyHertz);
    }

    /** A packet heard: the holder's modules take it if it is on the APRS frequency and copies. Server thread. */
    @Override
    public void receiveDataPacket(AntennaDataPacket packet) {
        Player player = getPlayer();
        if(player != null && !sendingDtmf && player.level() instanceof net.minecraft.server.level.ServerLevel level && tnc.receive(packet, level))
            com.arrl.radiocraft.common.moderation.ModerationHooks.heard(packet, this, level, player.blockPosition(), player, packet.getStrength());
    }

    @Override
    public void acceptVoicePacket(ServerLevel level, short[] rawAudio, UUID sourcePlayer) {
        if(sendingDtmf || tnc.isSending())
            return; // The tones have the transmitter.
        LinkedList<SynchronousRadioState> currentlyTransmitting = new LinkedList<>();
        synchronized (this){
            for(SynchronousRadioState radio : radios) if(radio.canTransmit) currentlyTransmitting.add(radio);
        }
        if(!currentlyTransmitting.isEmpty()) // Operators listening in (/radio moderateall).
            com.arrl.radiocraft.common.radio.voice.RadioModeration.voice(level, sourcePlayer, rawAudio, currentlyTransmitting.getFirst().transmitFrequency());
        for(SynchronousRadioState radio : currentlyTransmitting) {    
            short[] audio = rawAudio.clone();
            for (int i = 0; i < audio.length; i++) {
                audio[i] = (short)Math.round(audio[i] * radio.micGain);
            }
            transmitAudioPacket(level, audio, Band.getBand("2m"), radio.transmitFrequency(), sourcePlayer, radio.ctcssTone);
        }
    }

    @Override
    public void receiveAudioPacket(AntennaVoicePacket packet) {
        if (this.isReceiving()) {
            receive(packet);
        }
    }

    /**
     * How loud the handheld's speaker plays at volume {@code gain} (1 is 100%): an audio taper, as a real volume control
     * has. Below 100% the level goes as the square of the setting, so each step down makes a real difference (10% is
     * a hundredth, -40 dB; 50% a quarter, -12 dB); 100% and above are as set.
     */
    public static double speakerLevel(float gain) {
        return gain >= 1.0F ? gain : (double)gain * gain;
    }

    /** How long splatter from a signal just off frequency is remembered: a few 20 ms voice frames. */
    private static final long ADJACENT_QRM_HOLD_NANOS = 100_000_000L;
    private double adjacentQrm = 0.0D;
    private long adjacentQrmNanos = Long.MIN_VALUE / 2;
    private final com.arrl.radiocraft.common.radio.voice.SquelchTail squelchTail = new com.arrl.radiocraft.common.radio.voice.SquelchTail();

    @Override
    public synchronized void receive(AntennaVoicePacket antennaPacket) {
        if(sendingDtmf || tnc.isSending())
            return; // The transmitter is keyed for the tones: the receiver hears nothing.
        boolean dataBurst = com.arrl.radiocraft.common.radio.voice.Afsk.isDataSource(antennaPacket.getSourcePlayer());
        if(this.isReceiving()) {

            Player player = getPlayer();

            if(receiveChannel == null)
                if (!openChannel()) return;

            if(receiveChannel.getEntity().getEntity() != player){
                receiveChannel.flush();
                receiveChannel.updateEntity(RadiocraftVoicePlugin.API.fromEntity(player));
            }

            float packetFrequency = antennaPacket.getFrequency();
            double packetStrength = antennaPacket.getStrength();

            //TODO make muffled sounding when not on hotbar (via low pass or the like, not just volume reduction)
            boolean isHeld = false;
            boolean shouldRecieve = false;
            float gain = 1.0f;
            double detuneStrength = 0.0D; // Best of the handhelds that hear it (FM: up to 2 kHz off, weaker).
            CrystalQuality crystal = CrystalQuality.GOOD; // That handheld's crystal, for its receiver noise.
            double qrm = 0.0D; // Splatter it puts on the handhelds it is only near (3-10 kHz or one channel step off).
            int channelStep = RadiocraftServerConfig.VHF_FREQUENCY_STEP.get();
            for(SynchronousRadioState state : this.radios) {
                if(state.canReceive && !(dataBurst && state.aprsMuted())) {
                    Detuning.Effect effect = Detuning.fm(state.frequency, packetFrequency, channelStep);
                    if(effect.audible() && !Ctcss.opens(state.rxTone, antennaPacket.getCtcssTone()))
                        continue; // Tone squelch: not this radio's tone.
                    if(effect.audible()) {
                        shouldRecieve = true;
                        if(effect.strength() > detuneStrength)
                            crystal = state.crystal;
                        detuneStrength = Math.max(detuneStrength, effect.strength());
                        if(state.itemLocation == HandheldLocation.HELD) {
                            isHeld = true;
                        }
                    }
                    else
                        qrm = Math.max(qrm, effect.qrm());
                }
                gain = state.gain;
            }

            if(!shouldRecieve) { // FM squelch stays shut; remember the splatter for the signal that is on frequency.
                if(qrm > 0.0D) {
                    adjacentQrm = qrm * packetStrength;
                    adjacentQrmNanos = System.nanoTime();
                }
                return;
            }
            packetStrength *= detuneStrength;
            AntennaPos heardAt = getAntennaPos();
            com.arrl.radiocraft.common.moderation.ModerationHooks.heard(antennaPacket, this, heardAt == null ? null : heardAt.level(), heardAt == null ? null : heardAt.position(),
                    player, packetStrength); // The holder heard it: a hearer of the logged transmission.
            double splatter = System.nanoTime() - adjacentQrmNanos <= ADJACENT_QRM_HOLD_NANOS ? Detuning.splatterOn(1.0D, adjacentQrm, packetStrength) : 0.0D;

            // FM receiver: AGC against the receiver's own noise, voice band, overdrive; aurora rasp beyond line of sight.
            AntennaPos source = antennaPacket.getSource() == null ? null : antennaPacket.getSource().getAntennaPos();
            AntennaPos here = getAntennaPos();
            boolean beyondLos = source != null && here != null && antennaPacket.getBand() != null
                    && source.world().distSqr(here.world()) > (double)antennaPacket.getBand().losRange() * antennaPacket.getBand().losRange();
            // FM: full volume however weak the signal; it fades into hiss and dropouts instead (RadioVoiceProcessor#fmFade).
            double level = (isHeld ? 1.0D : 0.5D) * speakerLevel(gain);
            long now = System.nanoTime();
            List<com.arrl.radiocraft.common.radio.voice.VoiceMix.Signal> slot = aligner.offer(new com.arrl.radiocraft.common.radio.voice.VoiceMix.Signal(
                    com.arrl.radiocraft.common.radio.voice.VoiceMix.key(antennaPacket.getSourcePlayer(), antennaPacket.getSource()), antennaPacket.getSourcePlayer(),
                    antennaPacket.getRawAudio(), level, beyondLos ? 1.0D : 0.0D, 0.0D, 0.0D, splatter, packetStrength), now);
            if(slot.isEmpty())
                return; // Another station leads: this frame goes out mixed with its next one.
            com.arrl.radiocraft.common.radio.voice.VoiceMix.Mixed mixed = renderer.render(slot,
                    com.arrl.radiocraft.common.radio.voice.SpeakerKind.BUILT_IN.apply(RadioVoiceProcessor.currentSettings()), 1.0D, now);
            short[] rawAudio = mixed.pcm();
            long runningTotal = 0;
            for(short sample : rawAudio)
                runningTotal += (long)sample * sample;

            //TODO rework receiving to be per handheld, when frequency support is added
            for(SynchronousRadioState state : this.radios) {
                if(state.canReceive && !(dataBurst && state.aprsMuted()) && Detuning.fm(state.frequency, packetFrequency, channelStep).audible() && Ctcss.opens(state.rxTone, antennaPacket.getCtcssTone())) {
                    state.runningSampleCount += rawAudio.length;
                    state.runningSampleSum += runningTotal;
                }
            }

            receiveChannel.send(encoder().encode(rawAudio));
            squelchTail.heard(mixed.loudest().fmSignal(), mixed.loudest().level(), mixed.loudest().source(), now);
        }
    }

    /** The squelch tail once a signal drops: a short burst of static through the handheld. Server thread, each tick. */
    private synchronized void tickSquelchTail(long gameTime) {
        List<short[]> frames = squelchTail.drain(System.nanoTime(), gameTime % 2 == 0 ? 3 : 2);
        if(frames.isEmpty() || receiveChannel == null || RadiocraftVoicePlugin.API == null || !isReceiving())
            return;
        de.maxhenkel.voicechat.api.opus.OpusEncoder encoder = encoder();
        for(short[] frame : frames)
            receiveChannel.send(encoder.encode(frame));
    }

    private de.maxhenkel.voicechat.api.opus.OpusEncoder encoder() {
        if(encoder == null)
            encoder = RadiocraftVoicePlugin.API.createEncoder();
        return encoder;
    }

    /** Lets go of the handheld's Opus encoder (the player left); a new one is made if it plays again. */
    public synchronized void closeAudio() {
        if(encoder != null) {
            encoder.close();
            encoder = null;
        }
    }

    public void setNetwork(AntennaNetwork network) {
        if(this.network != null)
            this.network.removeAntenna(this);
        network.addAntenna(this);
        this.network = network;
    }

    public void tick() {
        Player player = getPlayer();
        if(player != null) {
            tickSquelchTail(player.level().getGameTime());
            tickDtmf(player);
            tickAprs(player);
        }
        List<SynchronousRadioState> radios = player == null ? Collections.emptyList() : genHandheldStates(player);
        List<SynchronousRadioState> lastTickRadios;

        synchronized (this) {
            lastTickRadios = this.radios;
            this.radios = radios;

            if (radios.isEmpty()) {
                this.voicePosition = null;
                this.voiceLevel = null;
                this.antennaPos.set(null);
            } else {
                this.voicePosition = player.getEyePosition();
                this.voiceLevel = player.level();
                this.antennaPos.set(new AntennaPos(player.blockPosition(), player.level()));
                this.indoors = com.arrl.radiocraft.common.radio.terrain.TerrainPropagation.isIndoors(player.level(), BlockPos.containing(player.getEyePosition()));
            }
        }

        for(SynchronousRadioState state : lastTickRadios) if(state.item != null){
            IVHFHandheldCapability cap = state.item.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
            if(cap != null) {
                cap.setReceiveStrength(state.runningSampleCount == 0 ? 0f : (float)Math.sqrt((double) state.runningSampleSum / state.runningSampleCount));
            }
        }
    }
}
