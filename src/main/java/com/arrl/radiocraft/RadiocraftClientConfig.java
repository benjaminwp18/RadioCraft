package com.arrl.radiocraft;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings for each player's own game ({@code radiocraft-client.toml}); a server does not see or enforce them. */
public class RadiocraftClientConfig {

	public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
	public static final ModConfigSpec SPEC;

	public static final ModConfigSpec.ConfigValue<Boolean> SHOW_RADIO_SCREENS;
	public static final ModConfigSpec.ConfigValue<Boolean> SCROLL_TUNING;

	static {
		SHOW_RADIO_SCREENS = BUILDER.comment(" Show the frequency, mode and S-meter on the screens of radios that are on, within a few blocks")
				.define("show_radio_screens", true);
		SCROLL_TUNING = BUILDER.comment(" Sneak and turn the mouse wheel while looking at a radio to tune it (one channel step per notch); off, the wheel changes the hotbar slot as usual")
				.define("scroll_tuning", true);
		SPEC = BUILDER.build();
	}
}
