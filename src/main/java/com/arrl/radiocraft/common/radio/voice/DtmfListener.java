package com.arrl.radiocraft.common.radio.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A radio's DTMF decoder chip: it looks for key tones ({@link Dtmf#detect}) in each frame the radio receives, follows
 * every station heard on its own, and once a key's tone ends decides whether it got through. A key copies with
 * {@link Dtmf#copyChance} of the average signal over its frames, so on a weak or fading signal some keys of a code go
 * missing and others arrive. Frames come in on the voice chat thread; {@link #drain} runs on the server thread.
 */
public final class DtmfListener {

	/** A tone not heard for this many ticks has ended (its station stopped sending). */
	public static final int END_TICKS = 2;

	private static final class Tone {
		final char key;
		int frames = 0;
		double quality = 0.0D, rasp = 0.0D, splatter = 0.0D;
		long lastGameTime;

		Tone(char key) {
			this.key = key;
		}
	}

	private final Map<Object, Tone> tones = new HashMap<>();
	private final List<Character> copied = new ArrayList<>();
	private final Random random;

	public DtmfListener() {
		this(new Random());
	}

	public DtmfListener(Random random) {
		this.random = random;
	}

	/**
	 * A frame received from {@code source} (any key that tells stations apart) at {@code quality} (its strength over the
	 * copy threshold, 1 at the threshold), with {@code rasp} and {@code splatter} roughening it (0 to 1).
	 */
	public synchronized void frame(Object source, short[] pcm, double quality, double rasp, double splatter, long gameTime) {
		char key = Dtmf.detect(pcm);
		Tone tone = tones.get(source);
		if(tone != null && tone.key != key) { // The tone stopped or changed: that key is done.
			finish(tone);
			tones.remove(source);
			tone = null;
		}
		if(key == 0)
			return;
		if(tone == null) {
			tone = new Tone(key);
			tones.put(source, tone);
		}
		tone.frames++;
		tone.quality += quality;
		tone.rasp += rasp;
		tone.splatter += splatter;
		tone.lastGameTime = gameTime;
	}

	/** Ends the tones not heard for {@link #END_TICKS}, and hands over (and forgets) the keys copied since last time. */
	public synchronized List<Character> drain(long gameTime) {
		Iterator<Tone> it = tones.values().iterator();
		while(it.hasNext()) {
			Tone tone = it.next();
			if(gameTime - tone.lastGameTime >= END_TICKS) {
				finish(tone);
				it.remove();
			}
		}
		if(copied.isEmpty())
			return List.of();
		List<Character> out = new ArrayList<>(copied);
		copied.clear();
		return out;
	}

	/** Forgets everything, half heard tones too (the radio turned off). */
	public synchronized void clear() {
		tones.clear();
		copied.clear();
	}

	private void finish(Tone tone) {
		if(tone.frames < Dtmf.MIN_FRAMES)
			return; // Too short for a key press: a blip in the speech or the noise.
		double chance = Dtmf.copyChance(tone.quality / tone.frames, tone.rasp / tone.frames, tone.splatter / tone.frames);
		if(chance >= 1.0D || (chance > 0.0D && random.nextDouble() < chance))
			copied.add(tone.key);
	}
}
