package com.arrl.radiocraft.common.moderation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Morse read back from CW key buffers, as the moderation log writes it next to a CW transmission. */
class CwDecoderTest {

	/** {@code code} ("... --- ...", a space between letters, " / " between words) keyed with a dot of {@code unit} ticks, cut into 20-tick buffers. */
	private static List<TransmissionRecord.CwBuffer> key(String code, int unit) {
		List<Boolean> ticks = new ArrayList<>();
		for(int i = 0; i < 3; i++)
			ticks.add(false);
		for(char c : code.toCharArray()) {
			switch(c) {
				case '.' -> { add(ticks, true, unit); add(ticks, false, unit); }
				case '-' -> { add(ticks, true, 3 * unit); add(ticks, false, unit); }
				case ' ' -> add(ticks, false, 2 * unit);
				case '/' -> add(ticks, false, 4 * unit);
				default -> { }
			}
		}
		List<TransmissionRecord.CwBuffer> buffers = new ArrayList<>();
		for(int from = 0, id = 100; from < ticks.size(); from += 20, id++) {
			int mask = 0;
			for(int i = 0; i < 20 && from + i < ticks.size(); i++)
				if(ticks.get(from + i))
					mask |= 1 << i;
			buffers.add(new TransmissionRecord.CwBuffer(id, mask));
		}
		return buffers;
	}

	private static void add(List<Boolean> ticks, boolean down, int count) {
		for(int i = 0; i < count; i++)
			ticks.add(down);
	}

	@Test
	void readsLettersAndWords() {
		assertEquals("SOS", CwDecoder.decode(key("... --- ...", 2)));
		assertEquals("CQ DE K1ABC", CwDecoder.decode(key("-.-. --.- / -.. . / -.- .---- .- -... -.-.", 2)));
	}

	@Test
	void buffersOutOfOrderAreSorted() {
		List<TransmissionRecord.CwBuffer> buffers = new ArrayList<>(key("... --- ...", 2));
		java.util.Collections.reverse(buffers);
		assertEquals("SOS", CwDecoder.decode(buffers));
	}

	@Test
	void nothingKeyedIsEmpty() {
		assertEquals("", CwDecoder.decode(List.of(new TransmissionRecord.CwBuffer(0, 0))));
	}
}
