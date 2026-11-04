package com.arrl.radiocraft.client.screens.radios;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.datacomponents.HandheldMemory;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.items.VHFHandheldItem;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability;
import com.arrl.radiocraft.client.RadiocraftClientValues;
import com.arrl.radiocraft.client.screens.widgets.*;
import com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities;
import com.arrl.radiocraft.common.network.serverbound.SHandheldAprsPacket;
import com.arrl.radiocraft.common.network.serverbound.SHandheldAprsSettingsPacket;
import com.arrl.radiocraft.common.radio.data.AprsIcon;
import com.arrl.radiocraft.common.network.serverbound.SHandheldDtmfPacket;
import com.arrl.radiocraft.common.network.serverbound.SHandheldMemoryPacket;
import com.arrl.radiocraft.common.network.serverbound.SHandheldRadioUpdatePacket;
import com.arrl.radiocraft.common.radio.data.HandheldAprsStation;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.voice.Dtmf;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.voice.Ctcss;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenAxis;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class VHFHandheldScreen extends Screen {

    // Each crystal quality of the handheld has its own GUI textures (vhf_handheld_pure.png), the good one the plain name.
    protected ResourceLocation texture = Radiocraft.id("textures/gui/vhf_handheld.png");
    protected ResourceLocation widgetsTexture = Radiocraft.id("textures/gui/vhf_handheld_widgets.png");

    protected static final int imageWidth = 157;
    protected static final int imageHeight = 248;
    /** The battery pack drawn under the radio, as on the model in the hand. */
    private static final int BATTERY_HEIGHT = 14;
    protected int leftPos;
    protected int topPos;

    protected final int index;
    protected ItemStack item;
    protected IVHFHandheldCapability cap;

    protected static int FREQUENCY_ENTERING_TIMEOUT = 7000; //in millis
    /** How long MW is held to store a memory channel. */
    private static final long MEMORY_HOLD_MILLIS = 600L;
    /** How long a short notice ("NO MEM", "STORED") replaces the top line. */
    private static final long FLASH_MILLIS = 1200L;


    private final FrequencyEntryState frequencyEntryState = new FrequencyEntryState();

    protected Dial micGainDial;
    protected Dial gainDial;

    protected MenuState menuState = MenuState.DEFAULT;

    LedIndicator TX_LED, RX_LED, DATA_LED;

    MeterNeedleIndicator POWER_METER;

    // Reference to the power toggle so we can reset it later without creating threads
    protected ToggleButton powerToggle;
    // If > 0, time in millis at which the power toggle should be reset to false
    protected long powerToggleResetAt = 0L;

    private String flashText = "";
    private long flashUntil = 0L;

    @SuppressWarnings("this-escape")
    public VHFHandheldScreen(int index) {
        super(Component.translatable(Radiocraft.translationKey("screen", "vhf_handheld")));
        this.index = index;
        updateCap();

        if(this.cap == null) // IntelliJ is lying, this is not always true.
            onClose();

        RadiocraftClientValues.SCREEN_VOICE_ENABLED = true;
    }

    protected void updateCap(){

        if(Minecraft.getInstance().player == null) return; // just to get rid of IDE warning, if this happens we have bigger fish to fry

        this.item = Minecraft.getInstance().player.getInventory().getItem(index);
        this.cap = item.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
        String crystal = VHFHandheldItem.crystalOf(item).radioSuffix();
        this.texture = Radiocraft.id("textures/gui/vhf_handheld" + crystal + ".png");
        this.widgetsTexture = Radiocraft.id("textures/gui/vhf_handheld" + crystal + "_widgets.png");

        if(cap == null) onClose();
    }

    @Override
    protected void init() {
        super.init();
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight - BATTERY_HEIGHT) / 2;

        TX_LED = new LedIndicator(Component.literal("TX LED"), leftPos+ 32, topPos + 107, 10, 13, 157, 0, texture, 256, 256);
        RX_LED = new LedIndicator(Component.literal("RX LED"), leftPos + 44, topPos + 107, 10, 13, 167, 0, texture, 256, 256);
        DATA_LED = new LedIndicator(Component.literal("Data LED"), leftPos + 56, topPos + 107, 10, 13, 167, 0, texture, 256, 256);

        //     public MeterNeedleIndicator(Component name, MeterNeedleType mnt, int meterDimension, int x, int y, int width, int height, int u, int v, ResourceLocation texture, int textureWidth, int textureHeight) {
        POWER_METER = new MeterNeedleIndicator(Component.literal("Power"), MeterNeedleIndicator.MeterNeedleType.METER_HORIZONTAL, 33, leftPos + 33, topPos + 126, 2, 20, 232, 0, widgetsTexture, 256, 256);

    // Power toggle - store reference so we can reset it later on the client thread without spawning threads
    ToggleButton powerBtn = new ToggleButton(cap.isPowered(), leftPos + 1, topPos + 37, 18, 38, 0, 0, widgetsTexture, 256, 256, this::onPressPower).withPowerClick();
    this.powerToggle = powerBtn;
    addRenderableWidget(powerBtn); // Power
        addRenderableWidget(new HoldButton(leftPos - 1, topPos + 80, 20, 101, 36, 0, widgetsTexture, 256, 256, this::onPressPTT, this::onReleasePTT)); // PTT
        this.micGainDial = new Dial(leftPos + 66, topPos - 1, 37, 21, 76, 0, widgetsTexture, 256, 256, this::onMicGainUp, this::onMicGainDown);
        addRenderableWidget(this.micGainDial); // Mic gain
        this.gainDial = new Dial(leftPos + 111, topPos - 1, 37, 21, 76, 42, widgetsTexture, 256, 256, this::onGainUp, this::onGainDown);
        addRenderableWidget(this.gainDial); // Gain
        addRenderableWidget(new HoverableImageButton(leftPos + 105, topPos + 168, 18, 14, 94, 84, 76, 84, widgetsTexture, 256, 256, this::onFrequencyButtonUp)); // Frequency up button
        addRenderableWidget(new HoverableImageButton(leftPos + 125, topPos + 168, 18, 14, 94, 98, 76, 98, widgetsTexture, 256, 256, this::onFrequencyButtonDown)); // Frequency down button
        //number buttons
        addRenderableWidget(new HoverableImageButton(leftPos + 54, topPos + 225, 20, 14, 172, 0, 152, 0, widgetsTexture, 256, 256, (b) -> this.onNum(0)));
        for (int i = 1; i < 10; i += 1) {
            final int num = i;
            addRenderableWidget(new HoverableImageButton(leftPos + 29 + ((i-1)%3)*25, topPos + 168 + ((i-1)/3)*19, 20, 14, 172, i*14, 152, i*14, widgetsTexture, 256, 256, (b) -> this.onNum(num)));
        }
        addRenderableWidget(new HoverableImageButton(leftPos + 29, topPos + 225, 20, 14, 172, 140, 152, 140, widgetsTexture, 256, 256, b -> onStar())); // *
        addRenderableWidget(new HoverableImageButton(leftPos + 79, topPos + 225, 20, 14, 172, 154, 152, 154, widgetsTexture, 256, 256, b -> onPound())); // #

        addRenderableWidget(new HoverableImageButton(leftPos + 103, topPos + 225, 42, 14, 192, 112, 192, 42, widgetsTexture, 256, 256, this::onPressEnter));
        addRenderableWidget(new HoverableImageButton(leftPos + 103, topPos + 187, 20, 14, 192, 70, 192, 0, widgetsTexture, 256, 256, b -> toggleMenu(MenuState.TX_TONE))); // TXT
        addRenderableWidget(new HoverableImageButton(leftPos + 125, topPos + 187, 20, 14, 192, 84, 192, 14, widgetsTexture, 256, 256, b -> toggleMenu(MenuState.RX_TONE))); // RXT
        addRenderableWidget(new TapHoldButton(leftPos + 99, topPos + 206, 23, 14, 192, 98, 192, 28, widgetsTexture, 256, 256, MEMORY_HOLD_MILLIS, this::onTapMemory, this::onHoldMemory)); // MW
        addRenderableWidget(new HoverableImageButton(leftPos + 121, topPos + 206, 28, 14, 192, 126, 192, 56, widgetsTexture, 256, 256, b -> onAprs())); // APRS

        addRenderableWidget(TX_LED);
        addRenderableWidget(RX_LED);
        addRenderableWidget(DATA_LED);
        addRenderableWidget(POWER_METER);
    }

    /**
     * DEFAULT shows the frequency (and the memory channel in memory mode); TXT and RXT open the TX and RX tone pages,
     * ENTER the OFFSET page, and on those the arrows change the setting. # opens the DTMF page, where the keys build a
     * string that ENTER sends. Holding MW opens MEMORY_STORE, then NAME_ENTRY to name the channel. * opens SETUP
     * (what memory channels show big; renaming the channel in use). APRS opens the APRS pages (with the module
     * installed).
     */
    protected enum MenuState{
        DEFAULT,
        SET_FREQ,
        TX_TONE,
        RX_TONE,
        OFFSET,
        DTMF,
        MEMORY_STORE,
        NAME_ENTRY,
        SETUP,
        APRS
    }

    /** Shows {@code text} on the top line for a moment. */
    private void flash(String text) {
        flashText = text;
        flashUntil = System.currentTimeMillis() + FLASH_MILLIS;
    }

    /** TXT or RXT: opens that tone page, or closes it back to the frequency if it is already open. */
    private void toggleMenu(MenuState page) {
        if(!cap.isPowered())
            return;
        frequencyEntryState.reset();
        menuState = menuState == page ? MenuState.DEFAULT : page;
    }

    // -------------------- DTMF --------------------

    /** The keys typed on the DTMF page, sent together by ENTER. */
    private String dtmfEntry = "";
    private static final int DTMF_SHOWN = 10;

    /** # opens the DTMF page; on it, # is a key like the others. */
    private void onPound() {
        if(!cap.isPowered())
            return;
        if(menuState == MenuState.DTMF) {
            typeDtmf('#');
            return;
        }
        frequencyEntryState.reset();
        dtmfEntry = "";
        menuState = MenuState.DTMF;
    }

    /** On the DTMF page a key; anywhere else * opens and closes the SETUP menu. */
    private void onStar() {
        if(!cap.isPowered())
            return;
        if(menuState == MenuState.DTMF) {
            typeDtmf('*');
            return;
        }
        if(typingText())
            return;
        frequencyEntryState.reset();
        setupItem = 0;
        menuState = menuState == MenuState.SETUP ? MenuState.DEFAULT : MenuState.SETUP;
    }

    // -------------------- SETUP --------------------

    private static final int SETUP_LINES = 2;
    private int setupItem = 0;

    private static String displayText(HandheldMemory.Display display) {
        return switch(display) {
            case NAME -> "NAME";
            case FREQUENCY -> "FREQ";
            case CHANNEL -> "CH NO";
        };
    }

    /** ENTER on SETUP: DISPLAY steps through name, frequency and channel number; NAME CH renames the channel in use. */
    private void setupEnter() {
        HandheldMemory memory = memory();
        if(setupItem == 0) {
            setMemory(memory.withDisplay(memory.display().next()));
            return;
        }
        if(!memory.memoryMode() || memory.get(memory.channel()) == null) {
            flash("MEM MODE ONLY");
            return;
        }
        startNaming(memory.channel());
    }

    // -------------------- CHANNEL NAMES --------------------

    private int nameSlot = 0; // The channel being named (NAME_ENTRY).
    private String nameDraft = "";

    private void startNaming(int slot) {
        HandheldMemory.Channel channel = memory().get(slot);
        nameSlot = slot;
        nameDraft = channel == null ? "" : channel.name();
        menuState = MenuState.NAME_ENTRY;
    }

    /** ENTER on NAME_ENTRY: the channel takes the name typed (empty for none). */
    private void saveName() {
        HandheldMemory memory = memory();
        HandheldMemory.Channel channel = memory.get(nameSlot);
        if(channel != null)
            setMemory(memory.store(channel.named(nameDraft)));
        flash(nameDraft.isBlank() ? "M" + nameSlot + " NO NAME" : "M" + nameSlot + " " + HandheldMemory.sanitizeName(nameDraft));
        menuState = MenuState.DEFAULT;
    }

    /** The text being typed (a channel name or a beacon comment). */
    private String draft() {
        return menuState == MenuState.NAME_ENTRY ? nameDraft : commentDraft;
    }

    private void setDraft(String text) {
        if(menuState == MenuState.NAME_ENTRY)
            nameDraft = text.length() > HandheldMemory.NAME_MAX ? text.substring(0, HandheldMemory.NAME_MAX) : text;
        else
            commentDraft = text.length() > TncPacket.BEACON_TEXT_MAX ? text.substring(0, TncPacket.BEACON_TEXT_MAX) : text;
    }

    private void typeDtmf(char key) {
        if(Dtmf.isKey(key) && dtmfEntry.length() < Dtmf.MAX_DIGITS)
            dtmfEntry = dtmfEntry + Character.toUpperCase(key);
    }

    /** Sends the keys typed: the radio keys up for them by itself. */
    private void sendDtmf() {
        for(char key : dtmfEntry.toCharArray())
            SHandheldDtmfPacket.send(index, key);
        flash("DTMF SENT");
        dtmfEntry = "";
        menuState = MenuState.DEFAULT;
    }

    // -------------------- MEMORY --------------------

    /** The slot shown on the MEMORY_STORE page. */
    private int storeSlot = 0;

    private HandheldMemory memory() {
        return item.getOrDefault(RadiocraftDataComponent.HANDHELD_MEMORY.value(), HandheldMemory.EMPTY);
    }

    private void setMemory(HandheldMemory memory) {
        item.set(RadiocraftDataComponent.HANDHELD_MEMORY.value(), memory);
        SHandheldMemoryPacket.send(index, memory);
    }

    /** The radio's present settings as a channel for {@code slot}. */
    private HandheldMemory.Channel currentSettings(int slot) {
        return new HandheldMemory.Channel(slot, cap.getFrequencyHertz(), cap.getCtcssTone(), cap.getRxTone(), cap.getOffsetHz());
    }

    /** Puts {@code channel}'s frequency, tones and offset on the radio. */
    private void apply(HandheldMemory.Channel channel) {
        cap.setFrequencyHertz(channel.frequency());
        cap.setCtcssTone(channel.ctcssTone());
        cap.setRxTone(channel.rxTone());
        cap.setOffsetHz(channel.offsetHz());
        updateServer();
    }

    /** MW tapped: VFO to memory mode and back (on the store page: back out of it). */
    private void onTapMemory() {
        if(!cap.isPowered())
            return;
        frequencyEntryState.reset();
        if(menuState == MenuState.MEMORY_STORE || menuState == MenuState.NAME_ENTRY) { // Backs out (the channel keeps its old name).
            menuState = MenuState.DEFAULT;
            return;
        }
        menuState = MenuState.DEFAULT;
        HandheldMemory memory = memory();
        if(memory.memoryMode()) {
            if(memory.vfo() != null)
                apply(memory.vfo());
            setMemory(memory.leaveMemory());
            flash("VFO");
            return;
        }
        if(memory.channels().isEmpty()) {
            flash("NO MEM");
            return;
        }
        int slot = memory.get(memory.channel()) != null ? memory.channel() : memory.channels().getFirst().slot();
        setMemory(memory.enterMemory(slot, currentSettings(0)));
        apply(memory.get(slot));
    }

    /** MW held: the store page, on the channel in use or the first free slot. */
    private void onHoldMemory() {
        if(!cap.isPowered())
            return;
        frequencyEntryState.reset();
        HandheldMemory memory = memory();
        storeSlot = memory.memoryMode() ? memory.channel() : 0;
        if(!memory.memoryMode())
            while(storeSlot < HandheldMemory.SLOTS - 1 && memory.get(storeSlot) != null)
                storeSlot++;
        menuState = MenuState.MEMORY_STORE;
    }

    /** ENTER on the store page: the radio's settings go into the slot shown. */
    private void storeMemory() {
        HandheldMemory memory = memory().store(currentSettings(storeSlot));
        if(memory.memoryMode())
            memory = memory.withChannel(storeSlot);
        setMemory(memory);
        startNaming(storeSlot); // Straight on to naming it; ENTER keeps the name it had.
    }

    /** In memory mode: on to the channel in {@code slot}, if one is stored there. */
    private void selectChannel(int slot) {
        HandheldMemory memory = memory();
        HandheldMemory.Channel channel = memory.get(slot);
        if(channel == null) {
            flash("M" + slot + " UNUSED");
            return;
        }
        setMemory(memory.withChannel(slot));
        apply(channel);
    }

    /** Frequency, then the tone and offset markers, of a stored channel, for the small line. */
    private static String describe(HandheldMemory.Channel channel) {
        String marks = (channel.offsetHz() == 0 ? "" : channel.offsetHz() < 0 ? " -" : " +") + (channel.ctcssTone() > 0 ? "T" : "") + (channel.rxTone() > 0 ? "R" : "");
        return String.format("%.3f", channel.frequency() / 1_000_000.0f) + marks;
    }

    // -------------------- APRS --------------------

    /** The pages under APRS, each a level down from the one before; APRS goes back up a level. */
    private enum AprsPage { MENU, STATIONS, STATION, MESSAGES, MESSAGE, SEND_TEXT, SEND_TO, BEACON, BEACON_TEXT }

    /** Lines on the APRS menu and on the BEACON page. */
    private static final int APRS_MENU_LINES = 5, BEACON_LINES = 5;

    private AprsPage aprsPage = AprsPage.MENU;
    private int aprsMenuItem = 0;
    private int aprsCursor = 0; // The station, message, text or recipient chosen on the page shown.
    private String aprsOpenStation = ""; // The station open on STATION.
    private int aprsOpenMessage = 0; // The message open on MESSAGE, by its place in the log.
    private long aprsOpenedAt = 0L; // When MESSAGE opened: its text scrolls from the start.
    private String aprsText = ""; // The canned text chosen to send.
    private String commentDraft = ""; // The beacon comment being typed (BEACON_TEXT).

    @Nullable
    private HandheldAprs aprs() {
        return item.get(RadiocraftDataComponent.HANDHELD_APRS.value());
    }

    private void onAprs() {
        if(!cap.isPowered())
            return;
        HandheldAprs aprs = aprs();
        if(aprs == null) {
            flash("NO APRS");
            return;
        }
        frequencyEntryState.reset();
        if(menuState != MenuState.APRS) {
            menuState = MenuState.APRS;
            aprsPage = AprsPage.MENU;
            return;
        }
        switch(aprsPage) { // APRS goes back a level.
            case MENU -> menuState = MenuState.DEFAULT;
            case STATIONS, MESSAGES, SEND_TEXT -> aprsPage = AprsPage.MENU;
            case STATION -> aprsPage = AprsPage.STATIONS;
            case MESSAGE -> aprsPage = AprsPage.MESSAGES;
            case SEND_TO -> aprsPage = AprsPage.SEND_TEXT;
            case BEACON -> {
                aprsPage = AprsPage.MENU;
                aprsCursor = 0;
            }
            case BEACON_TEXT -> aprsPage = AprsPage.BEACON; // Leaves the comment as it was.
        }
    }

    /** Stations heard, nearest first. */
    private List<HandheldAprs.Station> stationsByDistance(HandheldAprs aprs) {
        Player player = Minecraft.getInstance().player;
        if(player == null)
            return aprs.stations();
        BlockPos here = player.blockPosition();
        return aprs.stations().stream().sorted(Comparator.comparingDouble(station -> station.pos().distSqr(here))).toList();
    }

    /** Who a message can go to: everyone, then the stations heard and messaged. */
    private List<String> recipients(HandheldAprs aprs) {
        Set<String> out = new LinkedHashSet<>();
        out.add(TncPacket.EVERYONE);
        for(HandheldAprs.Station station : stationsByDistance(aprs))
            out.add(station.callsign());
        for(HandheldAprs.Message message : aprs.messages())
            out.add(message.peer());
        return new ArrayList<>(out);
    }

    /** How many entries the APRS page shown lists (for the arrows). */
    private int aprsEntries(HandheldAprs aprs) {
        return switch(aprsPage) {
            case MENU -> APRS_MENU_LINES;
            case BEACON -> BEACON_LINES;
            case STATIONS -> aprs.stations().size();
            case MESSAGES -> aprs.messages().size();
            case SEND_TEXT -> HandheldAprsStation.CANNED.size();
            case SEND_TO -> recipients(aprs).size();
            default -> 0;
        };
    }

    /** Up (-1) or down (+1) a line on the APRS page; wraps round. */
    private void aprsScroll(int direction) {
        HandheldAprs aprs = aprs();
        if(aprs == null)
            return;
        int entries = aprsEntries(aprs);
        if(entries <= 0)
            return;
        if(aprsPage == AprsPage.MENU)
            aprsMenuItem = Math.floorMod(aprsMenuItem + direction, entries);
        else
            aprsCursor = Math.floorMod(aprsCursor + direction, entries);
    }

    /** ENTER on an APRS page. */
    private void aprsEnter() {
        HandheldAprs aprs = aprs();
        if(aprs == null) {
            menuState = MenuState.DEFAULT;
            return;
        }
        switch(aprsPage) {
            case MENU -> {
                aprsCursor = 0;
                switch(aprsMenuItem) {
                    case 0 -> aprsPage = AprsPage.STATIONS;
                    case 1 -> aprsPage = AprsPage.MESSAGES;
                    case 2 -> aprsPage = AprsPage.SEND_TEXT;
                    case 3 -> aprsPage = AprsPage.BEACON;
                    case 4 -> {
                        SHandheldAprsPacket.send(index, SHandheldAprsPacket.Action.MUTE, aprs.mute() ? 0 : 1);
                        item.set(RadiocraftDataComponent.HANDHELD_APRS.value(), aprs.withMute(!aprs.mute()));
                    }
                    default -> {}
                }
            }
            case STATIONS -> {
                List<HandheldAprs.Station> stations = stationsByDistance(aprs);
                if(aprsCursor < stations.size()) {
                    aprsOpenStation = stations.get(aprsCursor).callsign();
                    aprsPage = AprsPage.STATION;
                }
            }
            case MESSAGES -> {
                if(aprsCursor < aprs.messages().size()) {
                    aprsOpenMessage = aprsCursor;
                    aprsOpenedAt = System.currentTimeMillis();
                    aprsPage = AprsPage.MESSAGE;
                    if(!aprs.messages().get(aprsCursor).read())
                        SHandheldAprsPacket.send(index, SHandheldAprsPacket.Action.READ, aprsCursor);
                }
            }
            case SEND_TEXT -> {
                aprsText = HandheldAprsStation.CANNED.get(Math.min(aprsCursor, HandheldAprsStation.CANNED.size() - 1));
                aprsCursor = 0;
                aprsPage = AprsPage.SEND_TO;
            }
            case SEND_TO -> {
                List<String> to = recipients(aprs);
                SHandheldAprsPacket.sendMessage(index, to.get(Math.min(aprsCursor, to.size() - 1)), aprsText);
                flash("MSG SENT");
                aprsPage = AprsPage.MENU;
            }
            case BEACON -> {
                HandheldAprs.Settings old = aprs.settings();
                switch(aprsCursor) {
                    case 0 -> setAprsSettings(aprs, old.withBeacon(!old.beacon()));
                    case 1 -> setAprsSettings(aprs, new HandheldAprs.Settings(old.beacon(), old.mute(), AprsIcon.byName(old.icon()).next().name(), old.comment(), old.ssid(), old.interval()));
                    case 2 -> setAprsSettings(aprs, new HandheldAprs.Settings(old.beacon(), old.mute(), old.icon(), old.comment(), (old.ssid() + 1) % (HandheldAprs.MAX_SSID + 1), old.interval()));
                    case 3 -> setAprsSettings(aprs, new HandheldAprs.Settings(old.beacon(), old.mute(), old.icon(), old.comment(), old.ssid(), nextInterval(old.interval())));
                    default -> {
                        commentDraft = old.comment();
                        aprsPage = AprsPage.BEACON_TEXT;
                    }
                }
            }
            case BEACON_TEXT -> {
                HandheldAprs.Settings old = aprs.settings();
                setAprsSettings(aprs, new HandheldAprs.Settings(old.beacon(), old.mute(), old.icon(), commentDraft, old.ssid(), old.interval()));
                aprsPage = AprsPage.BEACON;
            }
            case STATION, MESSAGE -> {}
        }
    }

    /** Puts {@code settings} on the handheld here and on the server. */
    private void setAprsSettings(HandheldAprs aprs, HandheldAprs.Settings settings) {
        item.set(RadiocraftDataComponent.HANDHELD_APRS.value(), settings.beacon() && !aprs.beacon() ? aprs.withBeacon(true).withSettings(settings) : aprs.withSettings(settings));
        SHandheldAprsSettingsPacket.send(index, settings);
    }

    /** The beacon interval after {@code seconds} in the list of choices, round to the first after the last. */
    private static int nextInterval(int seconds) {
        for(int choice : HandheldAprs.INTERVALS)
            if(choice > seconds)
                return choice;
        return HandheldAprs.INTERVALS[0];
    }

    /** True while the holder is typing text on the screen (keys that close it are letters then). */
    private boolean typingText() {
        return menuState == MenuState.NAME_ENTRY || (menuState == MenuState.APRS && aprsPage == AprsPage.BEACON_TEXT);
    }

    /** The ribbed battery pack clipped under the radio, as wide as its case is inside the rim. */
    private void renderBatteryPack(GuiGraphics graphics) {
        int x0 = leftPos + 21, x1 = leftPos + 154, y0 = topPos + imageHeight - 1, y1 = y0 + BATTERY_HEIGHT;
        graphics.fill(x0, y0, x1, y1, 0xFF181A22); // Outline.
        graphics.fill(x0 + 1, y0, x1 - 1, y1 - 1, 0xFF2C2E34);
        for(int y = y0 + 2; y < y1 - 2; y += 3) { // Ribs.
            graphics.fill(x0 + 3, y, x1 - 3, y + 1, 0xFF464950);
            graphics.fill(x0 + 3, y + 1, x1 - 3, y + 2, 0xFF1C1D21);
        }
        graphics.fill(x0 + 1, y0, x1 - 1, y0 + 1, 0xFF1C1D21); // The seam with the radio.
    }

    // -------------------- THE LCD --------------------

    // The glass inside the green screen's frame, in the radio's texture.
    private static final int LCD_X = 79, LCD_Y = 115;
    /** How long the backlight stays on after a key. */
    private static final long BACKLIGHT_MILLIS = 5000L;
    private long backlightUntil = 0L;

    /** A key or click: the backlight comes on. */
    private void backlight() {
        backlightUntil = System.currentTimeMillis() + BACKLIGHT_MILLIS;
    }

    /** Draws the whole LCD for the page shown. Off, it is blank glass. */
    private void renderLcd(GuiGraphics graphics, @Nullable HandheldAprs aprs) {
        HandheldLcd lcd = new HandheldLcd(graphics, leftPos + LCD_X, topPos + LCD_Y);
        boolean on = cap.isPowered();
        lcd.clear(on && System.currentTimeMillis() < backlightUntil);
        if(!on)
            return;
        if(System.currentTimeMillis() < flashUntil) {
            lcd.header("", "");
            String text = HandheldLcd.fit(flashText, HandheldLcd.WIDTH - 2);
            lcd.text(text, (HandheldLcd.WIDTH - HandheldLcd.textWidth(text)) / 2, 14);
            return;
        }
        switch(menuState) {
            case DEFAULT -> lcd.main(item, cap.isPTTDown() ? "TX" : cap.getReceiveStrength() > 0 ? "RX" : "");
            case SET_FREQ -> {
                lcd.header("SET FREQ", "");
                lcd.digitsCentered(entryDigits(), 10);
                lcd.textRight("ENT=OK", HandheldLcd.WIDTH - 1, 23);
            }
            case TX_TONE, RX_TONE -> {
                float tone = menuState == MenuState.TX_TONE ? cap.getCtcssTone() : cap.getRxTone();
                lcd.header(menuState == MenuState.TX_TONE ? "TX TONE" : "RX TONE", "^~");
                lcd.digitsCentered(tone > 0.0F ? String.format("%.1f", tone) : "OFF", 9);
                lcd.textRight(tone > 0.0F ? "HZ" : "", HandheldLcd.WIDTH - 1, 23);
            }
            case OFFSET -> {
                lcd.header("OFFSET", "^~");
                int offset = cap.getOffsetHz();
                lcd.digitsCentered(offset == 0 ? "0" : (offset < 0 ? "-" : "") + Math.abs(offset) / 1000, 9);
                lcd.textRight(offset == 0 ? "SIMPLEX" : "KHZ", HandheldLcd.WIDTH - 1, 23);
            }
            case DTMF -> {
                lcd.header("DTMF", "ENT=SEND");
                if(dtmfEntry.isEmpty())
                    lcd.text("TYPE KEYS", 1, 10);
                else {
                    lcd.text(HandheldLcd.fit(dtmfEntry, HandheldLcd.WIDTH - 2), 1, 9, HandheldLcd.INK, 1);
                    if(HandheldLcd.textWidth(dtmfEntry) > HandheldLcd.WIDTH - 2)
                        lcd.text(dtmfEntry.substring(HandheldLcd.fit(dtmfEntry, HandheldLcd.WIDTH - 2).length()), 1, 16);
                }
                lcd.text("~=DEL", 1, 23);
            }
            case MEMORY_STORE -> {
                lcd.header("STORE CH", "ENT=OK");
                lcd.digits(String.valueOf(storeSlot), 2, 10);
                HandheldMemory.Channel stored = memory().get(storeSlot);
                if(stored == null)
                    lcd.text("UNUSED", 14, 13);
                else {
                    lcd.text(String.format("%.3f", stored.frequency() / 1_000_000.0f), 14, 10);
                    int after = lcd.text(HandheldLcd.marks(stored.offsetHz(), stored.ctcssTone(), stored.rxTone()), 14, 18);
                    lcd.text(stored.name(), after + 2, 18);
                }
            }
            case NAME_ENTRY -> {
                lcd.header("NAME M" + nameSlot, nameDraft.length() + "/" + HandheldMemory.NAME_MAX);
                String shown = nameDraft + ((System.currentTimeMillis() / 400L) % 2L == 0L ? "_" : " ");
                lcd.text(shown, 2, 10, HandheldLcd.INK, 2);
                lcd.textRight("ENT=OK", HandheldLcd.WIDTH - 1, 23);
            }
            case SETUP -> {
                HandheldMemory memory = memory();
                HandheldMemory.Channel current = memory.memoryMode() ? memory.get(memory.channel()) : null;
                lcd.header("SETUP", "");
                lcd.list(List.of(
                        new String[] { "DISPLAY", displayText(memory.display()) },
                        new String[] { "NAME CH", current == null ? "-" : current.name().isEmpty() ? "M" + current.slot() : current.name() }), setupItem);
            }
            case APRS -> {
                if(aprs != null)
                    renderAprs(lcd, aprs);
            }
        }
    }

    /** The frequency being typed in, its digits not yet typed blank: "14 . " grows to "146.520". */
    private String entryDigits() {
        String full = String.format("%07.3f", frequencyEntryState.getFrequencyHz() / 1_000_000.0f);
        int typed = frequencyEntryState.digitsEntered();
        StringBuilder out = new StringBuilder();
        int digit = 0;
        for(char c : full.toCharArray()) {
            if(c == '.') {
                out.append('.');
                continue;
            }
            out.append(digit++ < typed ? c : ' ');
        }
        return out.toString();
    }

    /** Draws the APRS page shown. */
    private void renderAprs(HandheldLcd lcd, HandheldAprs aprs) {
        switch(aprsPage) {
            case MENU -> {
                lcd.header("APRS", aprs.mute() ? "MUTE" : "");
                long unread = aprs.messages().stream().filter(m -> m.incoming() && !m.read()).count();
                lcd.list(List.of(
                        new String[] { "STATIONS", String.valueOf(aprs.stations().size()) },
                        new String[] { "MESSAGES", unread > 0 ? unread + " NEW" : String.valueOf(aprs.messages().size()) },
                        new String[] { "SEND MSG", "" },
                        new String[] { "BEACON", aprs.beacon() ? "ON" : "OFF" },
                        new String[] { "MUTE", aprs.mute() ? "ON" : "OFF" }), aprsMenuItem);
            }
            case STATIONS -> {
                lcd.header("STATIONS", "");
                List<HandheldAprs.Station> stations = stationsByDistance(aprs);
                if(stations.isEmpty()) {
                    lcd.text("NONE HEARD", 1, 12);
                    return;
                }
                List<String[]> rows = new ArrayList<>();
                for(HandheldAprs.Station station : stations)
                    rows.add(new String[] { station.callsign(), distanceText(station.pos()) });
                lcd.list(rows, Math.min(aprsCursor, rows.size() - 1));
            }
            case STATION -> {
                HandheldAprs.Station station = aprs.stations().stream().filter(s -> s.callsign().equals(aprsOpenStation)).findFirst().orElse(null);
                if(station == null) {
                    aprsPage = AprsPage.STATIONS;
                    return;
                }
                lcd.header(station.callsign(), distanceText(station.pos()));
                lcd.text("X" + station.pos().getX(), 1, 8);
                lcd.text("Y" + station.pos().getY(), 1, 15);
                lcd.text("Z" + station.pos().getZ(), 1, 22);
                lcd.compass(HandheldLcd.WIDTH - 10, 17, 8, compassAngle(station.pos()));
            }
            case MESSAGES -> {
                lcd.header("MESSAGES", "");
                if(aprs.messages().isEmpty()) {
                    lcd.text("NO MESSAGES", 1, 12);
                    return;
                }
                List<String[]> rows = new ArrayList<>();
                for(HandheldAprs.Message message : aprs.messages())
                    rows.add(new String[] { (message.incoming() ? "<" : ">") + message.peer(),
                            message.incoming() ? (message.read() ? "" : "NEW") : message.status() == HandheldAprs.Status.ACKED ? "ACK" : "SENT" });
                lcd.list(rows, Math.min(aprsCursor, rows.size() - 1));
            }
            case MESSAGE -> {
                if(aprsOpenMessage >= aprs.messages().size()) {
                    aprsPage = AprsPage.MESSAGES;
                    return;
                }
                HandheldAprs.Message message = aprs.messages().get(aprsOpenMessage);
                lcd.header((message.incoming() ? "FROM " : "TO ") + message.peer(), "");
                lcd.marquee(message.text(), 12, System.currentTimeMillis() - aprsOpenedAt);
            }
            case SEND_TEXT -> {
                lcd.header("SEND MSG", "");
                List<String[]> rows = new ArrayList<>();
                for(String text : HandheldAprsStation.CANNED)
                    rows.add(new String[] { text });
                lcd.list(rows, Math.min(aprsCursor, rows.size() - 1));
            }
            case SEND_TO -> {
                lcd.header("TO", aprsText);
                List<String[]> rows = new ArrayList<>();
                for(String to : recipients(aprs))
                    rows.add(new String[] { to });
                lcd.list(rows, Math.min(aprsCursor, rows.size() - 1));
            }
            case BEACON -> {
                HandheldAprs.Settings settings = aprs.settings();
                lcd.header("BEACON", "");
                String call = aprs.callsign().isEmpty() ? "NONE" : HandheldAprs.withSsid(aprs.callsign(), settings.ssid());
                lcd.list(List.of(
                        new String[] { "BEACON", settings.beacon() ? "ON" : "OFF" },
                        new String[] { "ICON", settings.icon() },
                        new String[] { "CALL", call },
                        new String[] { "EVERY", intervalText(settings.interval()) },
                        new String[] { "TEXT", settings.comment().isEmpty() ? "-" : settings.comment() }), aprsCursor);
            }
            case BEACON_TEXT -> {
                lcd.header("COMMENT", commentDraft.length() + "/" + TncPacket.BEACON_TEXT_MAX);
                String shown = commentDraft + ((System.currentTimeMillis() / 400L) % 2L == 0L ? "_" : " ");
                int perLine = 14;
                for(int line = 0; line < 3 && line * perLine < shown.length(); line++)
                    lcd.text(shown.substring(line * perLine, Math.min(shown.length(), (line + 1) * perLine)), 1, 8 + line * HandheldLcd.LINE);
            }
        }
    }

    private static String intervalText(int seconds) {
        return seconds < 60 ? seconds + "S" : seconds / 60 + "M";
    }

    /** Blocks (or km past 1000) from the holder to {@code pos}. */
    private static String distanceText(BlockPos pos) {
        Player player = Minecraft.getInstance().player;
        if(player == null)
            return "";
        double blocks = Math.sqrt(player.blockPosition().distSqr(pos));
        return blocks >= 1000.0D ? String.format("%.1fK", blocks / 1000.0D) : Math.round(blocks) + "M";
    }

    /**
     * Where {@code target} is as the holder sees it, in radians clockwise from straight ahead; NaN when they are on it.
     */
    private static double compassAngle(BlockPos target) {
        Player player = Minecraft.getInstance().player;
        if(player == null)
            return Double.NaN;
        double dx = target.getX() + 0.5D - player.getX();
        double dz = target.getZ() + 0.5D - player.getZ();
        if(dx * dx + dz * dz < 4.0D)
            return Double.NaN;
        double yaw = Math.toRadians(player.getYRot());
        double facing = Math.atan2(-Math.sin(yaw), Math.cos(yaw)); // From +Z toward +X, as the target's angle below.
        return facing - Math.atan2(dx, dz); // Toward +X from +Z is to the holder's left, so clockwise is the other way.
    }

    @Override
    public void render(@NotNull GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
        // I removed the .super() call because for some reason it renders the backgrounds before buttons but after the other rendering of the actual menu.
        // I guess I could just add these items to the renderables list, but then you can't influence the powered state.

        updateCap();

        if(cap == null) return;

        if (menuState == MenuState.SET_FREQ && frequencyEntryState.hasTimedOut()) {
            frequencyEntryState.reset();
            menuState = MenuState.DEFAULT;
        }

        renderBackground(pGuiGraphics, pMouseX, pMouseY, pPartialTick);

        // Check if we need to reset the power toggle (scheduled earlier when trying to turn on with no battery)
        if (powerToggleResetAt > 0L && System.currentTimeMillis() >= powerToggleResetAt) {
            if (powerToggle != null) {
                powerToggle.isToggled = false;
            }
            powerToggleResetAt = 0L;
        }

        pGuiGraphics.blit(texture, leftPos, topPos, 0, 0, imageWidth, imageHeight);
        renderBatteryPack(pGuiGraphics);

        /*

        * Power meter shows transmitted power (based on user's voice amplitude?)
        * Data light turns on if there's a data transmission
        * RX light turns on if there's any signal being received (maybe we add squelch?)
        * Power meter shows receive strength if there's any signal being received (ignoring squelch)

         */

        if (cap.isPowered() && cap.isPTTDown()) {
            POWER_METER.setValue(1.0);
        } else if (cap.isPowered()) {
            if(cap.getReceiveStrength() <= 0f) {
                POWER_METER.setValue(Math.random() / 10.0);
            } else {
                POWER_METER.setValue(Math.log10(cap.getReceiveStrength() / 5f));
            }
        } else {
            POWER_METER.setValue(0.0);
        }

        HandheldAprs aprs = aprs();
        if (menuState == MenuState.APRS && aprs == null)
            menuState = MenuState.DEFAULT; // The module came out (another handheld in the slot).

        renderLcd(pGuiGraphics, aprs);

        if (micGainDial.isHoveredOrChanging()) {
            String strValue = String.format("%d%%", Math.round(cap.getMicGain() * 100));
            pGuiGraphics.drawString(
                this.font,
                strValue,
                micGainDial.getRectangle().getCenterInAxis(ScreenAxis.HORIZONTAL) - this.font.width(strValue) / 2,
                micGainDial.getRectangle().getCenterInAxis(ScreenAxis.VERTICAL) - this.font.lineHeight / 2,
                0xFFFFFF
            );
        }

        if (gainDial.isHoveredOrChanging()) {
            String strValue = String.format("%d%%", Math.round(cap.getGain() * 100));
            pGuiGraphics.drawString(
                this.font,
                strValue,
                gainDial.getRectangle().getCenterInAxis(ScreenAxis.HORIZONTAL) - this.font.width(strValue) / 2,
                gainDial.getRectangle().getCenterInAxis(ScreenAxis.VERTICAL) - this.font.lineHeight / 2,
                0xFFFFFF
            );
        }

        TX_LED.setIsOn(cap.isPowered() && cap.isPTTDown());
        RX_LED.setIsOn(cap.isPowered() && cap.getReceiveStrength() > 0);
        DATA_LED.setIsOn(cap.isPowered() && aprs != null && aprs.hasUnread() && (System.currentTimeMillis() / 500L) % 2L == 0L); // Blinks while a message waits.

        for (Renderable renderable : this.renderables) {
            renderable.render(pGuiGraphics, pMouseX, pMouseY, pPartialTick);
        }
    }

    public static void open(int index) {
        Minecraft.getInstance().setScreen(new VHFHandheldScreen(index));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        backlight();
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean value = super.mouseReleased(mouseX, mouseY, button);

        for(GuiEventListener listener : children()) { // Janky fix to make the dials detect a mouse release which isn't over them, forge allows for a mouse to go down but not back up.
            if(!listener.isMouseOver(mouseX, mouseY)) {
                if(listener instanceof Dial || listener instanceof TapHoldButton)
                    listener.mouseReleased(mouseX, mouseY, button);
            }
        }

        return value;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        backlight();
        if(typingText()) {
            if(keyCode == GLFW.GLFW_KEY_BACKSPACE && !draft().isEmpty())
                setDraft(draft().substring(0, draft().length() - 1));
            else if(keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                onPressEnter(null);
            else if(keyCode == GLFW.GLFW_KEY_ESCAPE)
                return super.keyPressed(keyCode, scanCode, modifiers);
            return true; // Letters are text here, not the inventory key.
        }
        if(keyCode == Minecraft.getInstance().options.keyInventory.getKey().getValue()) { // Manually close when E pressed since this isn't a normal inventory screen.
            onClose();
            return true;
        }
        if(menuState == MenuState.DTMF && keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if(!dtmfEntry.isEmpty())
                dtmfEntry = dtmfEntry.substring(0, dtmfEntry.length() - 1);
            return true;
        }
        if(menuState == MenuState.DTMF && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            onPressEnter(null);
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** On the DTMF page the keyboard's 0-9, *, # and A-D type keys too. */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if(typingText()) {
            if(codePoint >= 32 && codePoint < 127)
                setDraft(draft() + Character.toUpperCase(codePoint));
            return true;
        }
        if(menuState == MenuState.DTMF && Dtmf.isKey(codePoint)) {
            typeDtmf(codePoint);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        super.onClose();
        RadiocraftClientValues.SCREEN_PTT_PRESSED = false; // Make sure to stop recording player's mic when the UI is closed, in case they didn't let go of PTT
        RadiocraftClientValues.SCREEN_VOICE_ENABLED = false;
        RadiocraftClientValues.SCREEN_CW_ENABLED = false;
        if(cap.isPTTDown()) {
            cap.setPTTDown(false);
            updateServer();
        }
    }

    /**
     * Callback to do nothing, for readability.
     */
    protected void doNothing(AbstractWidget button) {}

    /**
     * callback for number buttons
     * @param num - which digit was pressed
     */
    protected void onNum(int num){
        if(!cap.isPowered()) return;

        if (menuState == MenuState.DTMF) {
            typeDtmf((char)('0' + num));
            return;
        }
        if (menuState == MenuState.MEMORY_STORE) {
            storeSlot = num;
            return;
        }
        if (typingText()) {
            setDraft(draft() + num);
            return;
        }
        if (menuState == MenuState.SETUP)
            return;
        if (menuState == MenuState.APRS)
            return;
        if (memory().memoryMode()) { // In memory mode the keys pick channels.
            menuState = MenuState.DEFAULT;
            selectChannel(num);
            return;
        }

        if (menuState != MenuState.SET_FREQ) {
            menuState = MenuState.SET_FREQ;
            frequencyEntryState.reset();
        }

        if (menuState == MenuState.SET_FREQ) {
            frequencyEntryState.appendDigit(num);
        }
    }

    /**
     * Callback for pressing enter
     */
    private void onPressEnter(@Nullable Button button) {
        if(!cap.isPowered()) return;
        switch(menuState) {
            case DEFAULT -> menuState = MenuState.OFFSET; // ENTER opens the offset page; ENTER there (or on a tone page) goes back.
            case OFFSET, TX_TONE, RX_TONE -> menuState = MenuState.DEFAULT;
            case DTMF -> {
                if(dtmfEntry.isEmpty())
                    menuState = MenuState.DEFAULT;
                else
                    sendDtmf();
            }
            case MEMORY_STORE -> storeMemory();
            case NAME_ENTRY -> saveName();
            case SETUP -> setupEnter();
            case APRS -> aprsEnter();
            case SET_FREQ -> {
                float requestedFrequency = frequencyEntryState.getFrequencyHz();
                if(requestedFrequency >= Band.getBand(2).minFrequency() && requestedFrequency <= Band.getBand(2).maxFrequency()) {
                    cap.setFrequencyHertz(
                            requestedFrequency
                    );
                }
                updateServer();
                frequencyEntryState.reset();
                menuState = MenuState.DEFAULT;
            }
        }
    }

    /** Pages where the arrows do something other than tune: true if they did. */
    private boolean changeSetting(int direction) {
        switch(menuState) {
            case DTMF -> { // Down deletes the last key.
                if(direction < 0 && !dtmfEntry.isEmpty())
                    dtmfEntry = dtmfEntry.substring(0, dtmfEntry.length() - 1);
                return true;
            }
            case MEMORY_STORE -> {
                storeSlot = Math.floorMod(storeSlot + direction, HandheldMemory.SLOTS);
                return true;
            }
            case NAME_ENTRY -> { // Down deletes the last letter.
                if(direction < 0 && !nameDraft.isEmpty())
                    nameDraft = nameDraft.substring(0, nameDraft.length() - 1);
                return true;
            }
            case SETUP -> {
                setupItem = Math.floorMod(setupItem - direction, SETUP_LINES);
                return true;
            }
            case APRS -> {
                aprsScroll(-direction); // Up goes up the list.
                return true;
            }
            case TX_TONE -> cap.setCtcssTone(Ctcss.step(cap.getCtcssTone(), direction));
            case RX_TONE -> cap.setRxTone(Ctcss.step(cap.getRxTone(), direction));
            case OFFSET -> cap.setOffsetHz(Ctcss.nextOffset(cap.getOffsetHz()));
            default -> {
                HandheldMemory memory = memory();
                if(!memory.memoryMode())
                    return false;
                selectChannel(memory.stepChannel(memory.channel(), direction)); // In memory mode the arrows step through the channels.
                return true;
            }
        }
        updateServer();
        return true;
    }

    /**
     * Callback for frequency up buttons.
     */
    protected void onFrequencyButtonUp(Button button) {
        if(!cap.isPowered()) return;
        if(changeSetting(1)) return;
        cap.setFrequencyHertz(
                Math.min( //ServerConfig is synced on game join, so no further checking is necessary
                        cap.getFrequencyHertz() + RadiocraftServerConfig.VHF_FREQUENCY_STEP.get(),
                        Band.getBand(2).maxFrequency()
                )
        );
        updateServer();
    }

    /**
     * Callback for frequency down buttons.
     */
    protected void onFrequencyButtonDown(Button button) {
        if(!cap.isPowered()) return;
        if(changeSetting(-1)) return;
        cap.setFrequencyHertz(
                Math.max(  //ServerConfig is synced on game join, so no further checking is necessary
                        cap.getFrequencyHertz() - RadiocraftServerConfig.VHF_FREQUENCY_STEP.get(),
                        Band.getBand(2).minFrequency()
                )
        );
        updateServer();
    }

    /**
     * Callback for gain dial up.
     */
    protected void onGainUp(Dial dial) {
        cap.setGain(
                Math.min(
                        cap.getGain() + 0.1f,
                        RadiocraftServerConfig.HANDHELD_MAX_GAIN.get().floatValue()
                )
        );
        updateServer();
    }

    /**
     * Callback for gain dial down.
     */
    protected void onGainDown(Dial dial) {
        cap.setGain(
                Math.max(
                        cap.getGain() - 0.1f,
                        0.0f
                )
        );
        updateServer();
    }

    /**
     * Callback for mic gain dial up.
     */
    protected void onMicGainUp(Dial dial) {
        cap.setMicGain(
                Math.min(
                        cap.getMicGain() + 0.1f,
                        RadiocraftServerConfig.HANDHELD_MAX_MIC_GAIN.get().floatValue()
                )
        );
        updateServer();
    }

    /**
     * Callback for gain dial down.
     */
    protected void onMicGainDown(Dial dial) {
        cap.setMicGain(
                Math.max(
                        cap.getMicGain() - 0.1f,
                        0.0f
                )
        );
        updateServer();
    }

    /**
     * Callback for pressing a PTT button.
     */
    protected void onPressPTT(HoldButton button) {
        //RadiocraftPackets.sendToServer(new SHandheldPTTPacket(index, true));
        cap.setPTTDown(true);
        RadiocraftClientValues.SCREEN_PTT_PRESSED = true;
        updateServer();
    }

    /**
     * Callback for releasing a PTT button.
     */
    protected void onReleasePTT(HoldButton button) {
        //RadiocraftPackets.sendToServer(new SHandheldPTTPacket(index, false));
        cap.setPTTDown(false);
        RadiocraftClientValues.SCREEN_PTT_PRESSED = false;
        updateServer();
    }

    /**
     * Callback to toggle power on a device.
     */
    protected void onPressPower(ToggleButton button) {
        // Check if trying to turn on
        if (!cap.isPowered()) {
            // Check if radio has battery
            net.neoforged.neoforge.energy.IEnergyStorage energyStorage =
                minecraft.player.getInventory().getItem(index).getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);

            if (energyStorage != null && energyStorage.getEnergyStored() > 0) {
                // Has battery - turn on
                cap.setPowered(true);
                updateServer();
            } else {
                // No battery - show message and delay button reset for visual feedback
                minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.radiocraft.radio_battery_empty"),
                    true
                );

                // Schedule button reset after ~200ms for visual feedback by setting a timestamp
                // We set powerToggleResetAt and let the main client thread clear the toggle in render().
                powerToggleResetAt = System.currentTimeMillis() + 200L;
            }
        } else {
            // Turning off - always allow
            cap.setPowered(false);
            menuState = MenuState.DEFAULT;
            updateServer();
        }
    }

    private final class FrequencyEntryState {
        private static final int MAX_DIGITS = 6;

        private float frequencyHz;
        private int digitsEntered;
        private long lastInteractionAt;

        void appendDigit(int digit) {
            if (digitsEntered >= MAX_DIGITS) {
                return;
            }

            if (digitsEntered == 0) {
                frequencyHz = 0.0f;
            }

            float placeValue = (float) Math.pow(10.0, (MAX_DIGITS - digitsEntered) + 2.0);
            frequencyHz += placeValue * digit;
            digitsEntered = digitsEntered + 1;
            lastInteractionAt = System.currentTimeMillis();
        }

        float getFrequencyHz() {
            return frequencyHz;
        }

        int digitsEntered() {
            return digitsEntered;
        }

        boolean hasTimedOut() {
            if (digitsEntered <= 0) {
                return false;
            }
            long elapsed = System.currentTimeMillis() - lastInteractionAt;
            return elapsed > FREQUENCY_ENTERING_TIMEOUT;
        }

        void reset() {
            frequencyHz = 0.0f;
            digitsEntered = 0;
            lastInteractionAt = 0;
        }
    }

    protected void updateServer(){
        SHandheldRadioUpdatePacket.updateServer(index, cap);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
