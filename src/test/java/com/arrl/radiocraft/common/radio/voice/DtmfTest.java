package com.arrl.radiocraft.common.radio.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DtmfTest {

	@Test
	void keysUseTheStandardTones() {
		assertArrayEquals(new double[] { 697.0D, 1209.0D }, Dtmf.tones('1'));
		assertArrayEquals(new double[] { 941.0D, 1336.0D }, Dtmf.tones('0'));
		assertArrayEquals(new double[] { 941.0D, 1209.0D }, Dtmf.tones('*'));
		assertArrayEquals(new double[] { 941.0D, 1477.0D }, Dtmf.tones('#'));
		assertArrayEquals(new double[] { 852.0D, 1633.0D }, Dtmf.tones('c'));
		assertNull(Dtmf.tones('E'));
	}

	@Test
	void codesAreCleanedUp() {
		assertEquals("12*#AD", Dtmf.sanitize(" 1-2*#ad!"));
		assertEquals(Dtmf.MAX_DIGITS, Dtmf.sanitize("1234567890123456789").length());
		assertEquals("", Dtmf.sanitize(null));
	}

	/** Every key's own tones decode back to it, on every frame of the tone, and the gap after decodes to nothing. */
	@Test
	void everyKeyDecodesFromItsTones() {
		for(char key : Dtmf.ALL_KEYS.toCharArray()) {
			List<short[]> frames = Dtmf.frames(key);
			assertEquals(Dtmf.TONE_FRAMES + Dtmf.GAP_FRAMES, frames.size());
			for(int f = 0; f < Dtmf.TONE_FRAMES; f++)
				assertEquals(key, Dtmf.detect(frames.get(f)), "Frame " + f + " of " + key);
			for(int f = Dtmf.TONE_FRAMES; f < frames.size(); f++)
				assertEquals(0, Dtmf.detect(frames.get(f)), "Gap frame " + f + " of " + key);
		}
	}

	@Test
	void noiseSingleTonesAndSilenceAreNotKeys() {
		assertEquals(0, Dtmf.detect(RepeaterAudio.noise(0.5D, 1234L)));
		assertEquals(0, Dtmf.detect(RepeaterAudio.silence()));
		assertEquals(0, Dtmf.detect(RepeaterAudio.tone(1000.0D, 0.02D, 0.5D).get(0))); // The courtesy beep.
		assertEquals(0, Dtmf.detect(RepeaterAudio.tone(697.0D, 0.02D, 0.5D).get(0))); // Half a key.
	}

	/** A key buried in noise of the same level is still found: the decoder works on the tones, not the loudness. */
	@Test
	void keyUnderModerateNoiseStillDecodes() {
		short[] tone = Dtmf.frames('5').get(2), noise = RepeaterAudio.noise(0.1D, 99L);
		short[] mixed = new short[tone.length];
		for(int i = 0; i < tone.length; i++)
			mixed[i] = (short)(tone[i] + noise[i]);
		assertEquals('5', Dtmf.detect(mixed));
	}

	private static List<Character> listen(String code, double quality, Random random) {
		DtmfListener listener = new DtmfListener(random);
		List<Character> out = new ArrayList<>();
		long tick = 0;
		List<short[]> frames = Dtmf.frames(code);
		for(int f = 0; f < frames.size(); f++) {
			listener.frame("station", frames.get(f), quality, 0.0D, 0.0D, tick);
			if(f % 3 == 2) // About 2.5 frames a tick, as the server sends them.
				out.addAll(listener.drain(++tick));
		}
		out.addAll(listener.drain(tick + DtmfListener.END_TICKS + 1));
		return out;
	}

	@Test
	void strongSignalCopiesEveryKeyInOrder() {
		assertEquals(List.of('1', '2', '3', '#', '1'), listen("123#1", 1.5D, new Random(1)));
	}

	@Test
	void signalUnderTheFloorCopiesNothing() {
		assertTrue(listen("123456", Dtmf.COPY_FLOOR, new Random(2)).isEmpty());
	}

	/** Halfway between the floor and the threshold about half the keys get through, each on its own chance. */
	@Test
	void weakSignalDropsSomeKeys() {
		double quality = (Dtmf.COPY_FLOOR + 1.0D) / 2.0D;
		int copied = 0, sent = 0;
		Random random = new Random(3);
		for(int i = 0; i < 20; i++) {
			copied += listen("0123456789", quality, random).size();
			sent += 10;
		}
		assertTrue(copied > sent * 0.3D && copied < sent * 0.7D, copied + " of " + sent);
	}

	/** A key cut off when the sender stops still counts once its tone has gone on long enough. */
	@Test
	void toneEndsWhenTheSenderStops() {
		DtmfListener listener = new DtmfListener(new Random(4));
		List<short[]> frames = Dtmf.frames('7');
		listener.frame("a", frames.get(0), 2.0D, 0.0D, 0.0D, 10);
		listener.frame("a", frames.get(1), 2.0D, 0.0D, 0.0D, 10);
		assertTrue(listener.drain(11).isEmpty(), "Ended too soon");
		assertEquals(List.of('7'), listener.drain(12));
	}

	@Test
	void stationsAreFollowedApart() {
		DtmfListener listener = new DtmfListener(new Random(5));
		List<short[]> one = Dtmf.frames('1'), two = Dtmf.frames('2');
		for(int f = 0; f < Dtmf.TONE_FRAMES; f++) {
			listener.frame("a", one.get(f), 2.0D, 0.0D, 0.0D, 0);
			listener.frame("b", two.get(f), 2.0D, 0.0D, 0.0D, 0);
		}
		List<Character> heard = listener.drain(DtmfListener.END_TICKS);
		assertTrue(heard.contains('1') && heard.contains('2') && heard.size() == 2, "Heard " + heard);
	}

	@Test
	void copyChanceRisesFromTheFloorToTheThreshold() {
		assertEquals(0.0D, Dtmf.copyChance(0.2D, 0.0D, 0.0D));
		assertEquals(1.0D, Dtmf.copyChance(1.0D, 0.0D, 0.0D));
		assertEquals(1.0D, Dtmf.copyChance(5.0D, 0.0D, 0.0D));
		assertTrue(Dtmf.copyChance(0.5D, 0.0D, 0.0D) < Dtmf.copyChance(0.8D, 0.0D, 0.0D));
		assertTrue(Dtmf.copyChance(2.0D, 1.0D, 0.0D) < 1.0D, "Aurora rasp costs nothing");
	}
}
