package com.arrl.radiocraft.common.init;

import com.arrl.radiocraft.Radiocraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class RadiocraftSoundEvents {

	public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, Radiocraft.MOD_ID);

	public static Supplier<SoundEvent> STATIC = SOUND_EVENTS.register("static", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("static"), 32.0F));
	public static Supplier<SoundEvent> MORSE = SOUND_EVENTS.register("morse", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("morse"), 32.0F));
	/** Broadband switching noise a radio picks up from redstone near its antenna (maintainer's recording, 2026-10-27, 19 s). */
	public static Supplier<SoundEvent> REDSTONE_RFI = SOUND_EVENTS.register("redstone_rfi", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("redstone_rfi"), 32.0F));
	/** The solar weather station printing its report (maintainer's sound, 2026-10-21). */
	public static Supplier<SoundEvent> TELETYPE = SOUND_EVENTS.register("teletype", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("teletype"), 16.0F));
	/** The bell at the end of the report, and on placing the station. */
	/** The machine's motor running. */
	/** The H19 terminal's fan and transformer hum, a loop played while its screen is open. */
	public static Supplier<SoundEvent> H19_WHIRR = SOUND_EVENTS.register("h19_whirr", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("h19_whirr"), 16.0F));
	public static Supplier<SoundEvent> TELETYPE_BACKGROUND = SOUND_EVENTS.register("teletype_background", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("teletype_background"), 16.0F));
	/** One keystroke of the machine printing; sounds.json picks one of fifteen at random. */
	public static Supplier<SoundEvent> TELETYPE_KEY = SOUND_EVENTS.register("teletype_key", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("teletype_key"), 16.0F));
	public static Supplier<SoundEvent> TELETYPE_DING = SOUND_EVENTS.register("teletype_ding", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("teletype_ding"), 16.0F));
	/** The printing sound with a fade at the end; not used yet. */
	public static Supplier<SoundEvent> TELETYPE_FADEOUT = SOUND_EVENTS.register("teletype_fadeout", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("teletype_fadeout"), 16.0F));
	/** The H19 running: its fan and flyback whine, looped at the terminal while it has power (S0lver's sounds, 2026-11-04, as are the next two). */
	public static Supplier<SoundEvent> H19_RUNNING = SOUND_EVENTS.register("h19_running", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("h19_running"), 12.0F));
	/** The H19's bell: as it boots, and when a message comes in. */
	public static Supplier<SoundEvent> H19_BELL = SOUND_EVENTS.register("h19_bell", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("h19_bell"), 16.0F));
	/** One key of the H19's keyboard; quieter, a character appearing on its screen. */
	public static Supplier<SoundEvent> H19_TYPE = SOUND_EVENTS.register("h19_type", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("h19_type"), 8.0F));
	/** A radio's power button latching in (on) and popping out (off). */
	public static Supplier<SoundEvent> POWER_IN = SOUND_EVENTS.register("power_in", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("power_in"), 8.0F));
	public static Supplier<SoundEvent> POWER_OUT = SOUND_EVENTS.register("power_out", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("power_out"), 8.0F));
	/** A recording of FM static; the voice path decodes the file itself (FmStatic), this is the same for the sound engine. */
	public static Supplier<SoundEvent> FM_STATIC = SOUND_EVENTS.register("fm_static", () -> SoundEvent.createFixedRangeEvent(Radiocraft.id("fm_static"), 32.0F));

}
