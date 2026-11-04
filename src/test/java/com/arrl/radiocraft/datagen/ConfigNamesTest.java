package com.arrl.radiocraft.datagen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigNamesTest {

	@Test
	void settingsReadAsWords() {
		assertEquals("Antenna Update Delay", ConfigNames.name("antenna_update_delay"));
		assertEquals("HF Frequency Step", ConfigNames.name("hf_frequency_step"));
		assertEquals("Alternator FE at 256 RPM", ConfigNames.name("alternator_fe_at_256_rpm"));
		assertEquals("Allow Aurora at Any Z Coordinate", ConfigNames.name("allow_aurora_at_any_z_coordinate"));
		assertEquals("HF Radio 10m Receive", ConfigNames.name("hf_radio_10m_receive"));
		assertEquals("Solar Panel Max Output", ConfigNames.name("solar_panel_max_output integer"));
		assertEquals("Desense Separation MHz", ConfigNames.name("desense_separation_mhz"));
	}

	@Test
	void bandSettingsNamedByWhatTheyAre() {
		assertEquals("Line of Sight Range", ConfigNames.name("10mm_los"));
		assertEquals("Shortest Skip (Night)", ConfigNames.name("80mm_minSkipNight"));
		assertEquals("Highest Frequency (Hz)", ConfigNames.name("2mm_maxFrequency"));
	}

	@Test
	void sectionsLoseTheRestartNote() {
		assertEquals("Antenna Options", ConfigNames.section("Antenna Options ( * = Restart game to take effect)"));
		assertEquals("Digital Interface (TNC)", ConfigNames.section("Digital Interface (TNC)"));
		assertEquals("10m Band", ConfigNames.section("10m band settings"));
		assertEquals("2m Band (VHF)", ConfigNames.section("2m band settings, also known as VHF"));
	}
}
