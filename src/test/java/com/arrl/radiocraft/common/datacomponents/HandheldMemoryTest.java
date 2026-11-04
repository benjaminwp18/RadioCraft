package com.arrl.radiocraft.common.datacomponents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HandheldMemoryTest {

	private static HandheldMemory.Channel channel(int slot, float mhz) {
		return new HandheldMemory.Channel(slot, mhz * 1_000_000.0f, 0.0f, 0.0f, 0);
	}

	@Test
	void storeReplacesTheSlotAndKeepsSlotOrder() {
		HandheldMemory memory = HandheldMemory.EMPTY.store(channel(5, 146.52f)).store(channel(1, 147.0f)).store(channel(5, 145.5f));
		assertEquals(2, memory.channels().size());
		assertEquals(1, memory.channels().get(0).slot());
		assertEquals(145.5f * 1_000_000.0f, memory.get(5).frequency());
		assertNull(memory.get(2));
	}

	@Test
	void slotsOutsideTheKeysAreDropped() {
		HandheldMemory memory = new HandheldMemory(java.util.List.of(channel(-1, 146.0f), channel(10, 146.0f), channel(3, 146.0f)), false, 42, null, HandheldMemory.Display.FREQUENCY).sanitized();
		assertEquals(1, memory.channels().size());
		assertEquals(HandheldMemory.SLOTS - 1, memory.channel());
	}

	@Test
	void stepChannelWrapsThroughUsedSlots() {
		HandheldMemory memory = HandheldMemory.EMPTY.store(channel(2, 146.0f)).store(channel(5, 146.1f)).store(channel(8, 146.2f));
		assertEquals(5, memory.stepChannel(2, 1));
		assertEquals(2, memory.stepChannel(8, 1));
		assertEquals(8, memory.stepChannel(2, -1));
		assertEquals(5, memory.stepChannel(3, 1)); // From an unused slot: the next used one up.
		assertEquals(2, memory.stepChannel(3, -1)); // And down.
		assertEquals(2, memory.stepChannel(9, 1));
		assertEquals(4, HandheldMemory.EMPTY.stepChannel(4, 1)); // Nothing stored: stays.
	}

	@Test
	void memoryModeRemembersTheVfo() {
		HandheldMemory.Channel dial = channel(0, 147.33f);
		HandheldMemory memory = HandheldMemory.EMPTY.store(channel(3, 146.52f)).enterMemory(3, dial);
		assertTrue(memory.memoryMode());
		assertEquals(dial.frequency(), memory.vfo().frequency());
		HandheldMemory back = memory.leaveMemory();
		assertFalse(back.memoryMode());
		assertNull(back.vfo());
		assertEquals(1, back.channels().size());
	}

	@Test
	void namesAreCleanedAndKept() {
		HandheldMemory memory = HandheldMemory.EMPTY.store(channel(4, 146.94f).named("  repeater west§"));
		assertEquals("REPEATE", memory.get(4).name()); // Upper case, printable, seven characters.
		HandheldMemory renamed = memory.store(memory.get(4).named("home"));
		assertEquals("HOME", renamed.get(4).name());
		assertEquals(146.94f * 1_000_000.0f, renamed.get(4).frequency());
	}

	@Test
	void displayModeCyclesAndSurvivesChanges() {
		HandheldMemory memory = HandheldMemory.EMPTY.withDisplay(HandheldMemory.Display.FREQUENCY.next());
		assertEquals(HandheldMemory.Display.CHANNEL, memory.display());
		assertEquals(HandheldMemory.Display.NAME, memory.display().next());
		assertEquals(HandheldMemory.Display.CHANNEL, memory.store(channel(1, 146.0f)).enterMemory(1, channel(0, 147.0f)).leaveMemory().display());
	}
}
