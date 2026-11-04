package com.arrl.radiocraft.common.radio.voice;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.radio.tape.OggDecoder;

import java.io.BufferedInputStream;
import java.io.InputStream;

/**
 * The static an FM receiver plays: the maintainer's {@code fm_static.ogg} (2026-11-04, 7.8 s of a real squelch opened on
 * nothing), decoded once and looped. Heard under a fading signal ({@link RadioVoiceProcessor#fmFade}) and in the squelch
 * tail ({@link SquelchTail}). Scaled so its loudness matches the softened white noise used before it, which is also what
 * plays if the file cannot be read.
 */
public final class FmStatic {

	private static final String FILE = "/assets/radiocraft/sounds/fm_static.ogg";
	/** RMS of the softened white noise this replaced: what the recording is scaled to. */
	private static final double TARGET_RMS = 1.0D / 3.0D;

	private static volatile float[] samples;
	private static volatile boolean loaded = false;

	private FmStatic() {
	}

	/** The recording, scaled; null if it could not be read. */
	private static float[] samples() {
		if(!loaded) {
			synchronized(FmStatic.class) {
				if(!loaded) {
					samples = load();
					loaded = true;
				}
			}
		}
		return samples;
	}

	private static float[] load() {
		try(InputStream in = FmStatic.class.getResourceAsStream(FILE)) {
			if(in == null) {
				Radiocraft.LOGGER.warn("No {}: FM static falls back to white noise", FILE);
				return null;
			}
			short[] pcm = OggDecoder.decode(new BufferedInputStream(in));
			if(pcm.length == 0)
				return null;
			double sum = 0.0D;
			for(short s : pcm)
				sum += (double)s * s;
			double rms = Math.sqrt(sum / pcm.length) / 32768.0D;
			float scale = (float)(rms > 0.0D ? TARGET_RMS / rms / 32768.0D : 0.0D);
			float[] out = new float[pcm.length];
			for(int i = 0; i < pcm.length; i++)
				out[i] = pcm[i] * scale;
			return out;
		}
		catch(Exception | LinkageError e) { // LinkageError: no JOrbis, as on a dev server (the shipped jar shades it in).
			Radiocraft.LOGGER.warn("Could not read {}: FM static falls back to white noise ({})", FILE, e.toString());
			return null;
		}
	}

	/** A place in the loop: each receiver plays from its own, so two side by side do not hiss in step. */
	public static final class Cursor {
		private long state;
		private int at = -1;
		private double soft = 0.0D;

		/** Starts at a place picked from {@code seed}. */
		public Cursor(long seed) {
			state = seed | 1L;
		}

		public Cursor() {
			this(System.nanoTime());
		}

		/** The next sample, about -1 to 1 with an RMS of 1/3. */
		public double next() {
			float[] loop = samples();
			if(loop == null) { // Softened white noise: hiss, not fizz.
				state ^= state << 13;
				state ^= state >>> 7;
				state ^= state << 17;
				soft = 0.5D * soft + 0.5D * (((state >>> 11) * 0x1.0p-53) * 2.0D - 1.0D);
				return soft;
			}
			if(at < 0)
				at = (int)Math.floorMod(state, (long)loop.length);
			double sample = loop[at];
			at = (at + 1) % loop.length;
			return sample;
		}
	}
}
