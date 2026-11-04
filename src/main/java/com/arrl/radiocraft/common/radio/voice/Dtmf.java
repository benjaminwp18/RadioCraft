package com.arrl.radiocraft.common.radio.voice;

import java.util.List;
import java.util.Locale;

/**
 * DTMF ("touch tone"): each key is two tones at once, one from a low group (the keypad's row) and one from a high
 * group (its column), the ITU-T Q.23 frequencies a telephone keypad sends:
 * <pre>
 *          1209  1336  1477  1633 Hz
 *    697     1     2     3     A
 *    770     4     5     6     B
 *    852     7     8     9     C
 *    941     *     0     #     D
 * </pre>
 * Sent as audio, 20 ms frames of 48 kHz PCM like voice, so every receiver hears the real tones, a repeater passes them
 * on, and a decoder finds them in what its radio receives with the Goertzel algorithm (one DFT bin per tone), the way a
 * hardware decoder chip does.
 */
public final class Dtmf {

	private Dtmf() {}

	public static final double[] LOW_HZ = { 697.0D, 770.0D, 852.0D, 941.0D };
	public static final double[] HIGH_HZ = { 1209.0D, 1336.0D, 1477.0D, 1633.0D };
	private static final String[] KEYS = { "123A", "456B", "789C", "*0#D" };

	/** Every key, in keypad order. */
	public static final String ALL_KEYS = "123A456B789C*0#D";
	/** The longest code a decoder takes, or an encoder sends at once. */
	public static final int MAX_DIGITS = 16;

	/** Each tone's frames: 100 ms of tone and 100 ms of silence, the speed a radio's auto-dialler sends at. */
	public static final int TONE_FRAMES = 5;
	public static final int GAP_FRAMES = 5;
	/** Level of each of the two tones (0 to 1): together they peak at 0.6, a firm but unclipped signal. */
	private static final double TONE_LEVEL = 0.3D;
	private static final double RAMP_SECONDS = 0.004D;
	private static final double SAMPLE_RATE = 48000.0D;

	/** A tone must last this many frames (40 ms, the standard's shortest) to count as a key press. */
	public static final int MIN_FRAMES = 2;

	/** True for 0-9, A-D, * and #. */
	public static boolean isKey(char c) {
		return ALL_KEYS.indexOf(Character.toUpperCase(c)) >= 0;
	}

	/** {@code text} upper cased, with anything that is not a key dropped, at most {@link #MAX_DIGITS} long. */
	public static String sanitize(String text) {
		StringBuilder out = new StringBuilder();
		if(text != null)
			for(char c : text.toUpperCase(Locale.ROOT).toCharArray())
				if(isKey(c) && out.length() < MAX_DIGITS)
					out.append(c);
		return out.toString();
	}

	/** The low (row) and high (column) tone of {@code key} in Hz, or null if it is not a key. */
	public static double[] tones(char key) {
		char c = Character.toUpperCase(key);
		for(int row = 0; row < 4; row++) {
			int column = KEYS[row].indexOf(c);
			if(column >= 0)
				return new double[] { LOW_HZ[row], HIGH_HZ[column] };
		}
		return null;
	}

	/** The key with tones {@code row} (low group) and {@code column} (high group). */
	public static char key(int row, int column) {
		return KEYS[row].charAt(column);
	}

	/** One key's tone and the silence after it, as 20 ms frames. Empty for anything that is not a key. */
	public static List<short[]> frames(char key) {
		double[] hz = tones(key);
		if(hz == null)
			return List.of();
		int tone = TONE_FRAMES * RepeaterAudio.FRAME;
		double[] samples = new double[tone + GAP_FRAMES * RepeaterAudio.FRAME];
		int ramp = (int)(RAMP_SECONDS * SAMPLE_RATE);
		for(int i = 0; i < tone; i++) {
			double envelope = Math.min(1.0D, Math.min(i, tone - 1 - i) / (double)ramp);
			double t = i / SAMPLE_RATE;
			samples[i] = (Math.sin(2.0D * Math.PI * hz[0] * t) + Math.sin(2.0D * Math.PI * hz[1] * t)) * TONE_LEVEL * envelope;
		}
		return RepeaterAudio.frames(samples);
	}

	/** {@code digits} one after the other as frames (see {@link #frames(char)}); anything not a key is skipped. */
	public static List<short[]> frames(String digits) {
		List<short[]> out = new java.util.ArrayList<>();
		for(char c : digits.toCharArray())
			out.addAll(frames(c));
		return out;
	}

	// -------------------- DECODING --------------------

	/** Quieter than this (RMS, 0 to 1) a frame holds no tones worth looking at. */
	private static final double MIN_RMS = 0.01D;
	/** Share of the frame's energy each tone of the pair needs, and both together. */
	private static final double MIN_TONE_SHARE = 0.15D;
	private static final double MIN_PAIR_SHARE = 0.6D;
	/** The runner-up in a group must be this far below the winner, so a chord or a whistle is not taken for a key. */
	private static final double MAX_RUNNER_UP = 0.25D;
	/** Most one tone may be louder than the other (twist), as a power ratio: 6 dB. */
	private static final double MAX_TWIST = 4.0D;

	/**
	 * The key whose two tones fill {@code frame}, or 0 for none: each tone of the pair must carry a good share of the
	 * frame's energy, stand well clear of the other tones in its group, and the two be within 6 dB of each other.
	 */
	public static char detect(short[] frame) {
		int n = frame.length;
		if(n == 0)
			return 0;
		double energy = 0.0D;
		for(short s : frame) {
			double x = s / 32768.0D;
			energy += x * x;
		}
		if(Math.sqrt(energy / n) < MIN_RMS)
			return 0;
		double[] low = new double[4], high = new double[4];
		for(int i = 0; i < 4; i++) {
			low[i] = share(frame, LOW_HZ[i], energy);
			high[i] = share(frame, HIGH_HZ[i], energy);
		}
		int row = best(low), column = best(high);
		double r = low[row], c = high[column];
		if(r < MIN_TONE_SHARE || c < MIN_TONE_SHARE || r + c < MIN_PAIR_SHARE)
			return 0;
		if(runnerUp(low, row) > r * MAX_RUNNER_UP || runnerUp(high, column) > c * MAX_RUNNER_UP)
			return 0;
		if(r > c * MAX_TWIST || c > r * MAX_TWIST)
			return 0;
		return key(row, column);
	}

	/**
	 * Share of {@code energy} (the sum of the squared samples) in a tone of {@code hz}, by the Goertzel algorithm:
	 * about 1 for a pure tone at {@code hz}, 0.5 each for two equal tones.
	 */
	static double share(short[] frame, double hz, double energy) {
		int n = frame.length;
		double coeff = 2.0D * Math.cos(2.0D * Math.PI * hz / SAMPLE_RATE);
		double s1 = 0.0D, s2 = 0.0D;
		for(short sample : frame) {
			double s = sample / 32768.0D + coeff * s1 - s2;
			s2 = s1;
			s1 = s;
		}
		double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
		return energy <= 0.0D ? 0.0D : power / (energy * n / 2.0D);
	}

	private static int best(double[] values) {
		int best = 0;
		for(int i = 1; i < values.length; i++)
			if(values[i] > values[best])
				best = i;
		return best;
	}

	private static double runnerUp(double[] values, int best) {
		double out = 0.0D;
		for(int i = 0; i < values.length; i++)
			if(i != best)
				out = Math.max(out, values[i]);
		return out;
	}

	// -------------------- COPY --------------------

	/** Below this share of the copy threshold no key gets through; at the threshold and above every one does. */
	public static final double COPY_FLOOR = 0.3D;
	/** Most a receiver may be tuned off a key's tones (an SSB pitch shift) and still copy them: about 1.5% of 697 Hz. */
	public static final double MAX_PITCH_SHIFT_HZ = 10.0D;

	/**
	 * Chance one key gets through, from how far its signal stood above the copy threshold on average ({@code quality},
	 * 1 at the threshold): none at {@link #COPY_FLOOR} or under, every one at 1 and over, an S curve between. Aurora
	 * rasp ({@code rasp}, 0 to 1) and splatter from a station next door (0 to 1) each lose some on top.
	 */
	public static double copyChance(double quality, double rasp, double splatter) {
		double x = Math.max(0.0D, Math.min(1.0D, (quality - COPY_FLOOR) / (1.0D - COPY_FLOOR)));
		double chance = x * x * (3.0D - 2.0D * x);
		chance *= 1.0D - 0.5D * Math.max(0.0D, Math.min(1.0D, rasp));
		chance *= 1.0D - Math.max(0.0D, Math.min(1.0D, splatter));
		return chance;
	}
}
