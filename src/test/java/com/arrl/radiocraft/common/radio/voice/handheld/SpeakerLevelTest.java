package com.arrl.radiocraft.common.radio.voice.handheld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeakerLevelTest {

	@Test
	void fullVolumeIsUnchangedAndLowStepsDropFast() {
		assertEquals(1.0D, PlayerRadio.speakerLevel(1.0F), 1e-9);
		assertEquals(1.5D, PlayerRadio.speakerLevel(1.5F), 1e-9); // Over 100% stays as set.
		assertEquals(0.01D, PlayerRadio.speakerLevel(0.1F), 1e-6); // -40 dB.
		assertEquals(0.25D, PlayerRadio.speakerLevel(0.5F), 1e-6);
		assertEquals(0.0D, PlayerRadio.speakerLevel(0.0F), 1e-9);
	}
}
