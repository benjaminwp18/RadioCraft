package com.arrl.radiocraft.common.radio.voice;

import java.util.List;

/**
 * The tones a repeater autopatch sends out through its repeater, as 20 ms frames like {@link RepeaterAudio}'s: the North
 * American call progress tones (dial tone, ringback, reorder) and two short beeps for a call ended or a dial cancelled.
 * None of them is a DTMF pair, so a decoder listening to the repeater takes none of them for a key.
 */
public final class AutopatchTones {

	private AutopatchTones() {}

	private static final double SAMPLE_RATE = RadioVoiceProcessor.SAMPLE_RATE;
	/** Level of each tone of a pair (0 to 1), like DTMF's: together they peak at 0.6. */
	private static final double LEVEL = 0.3D;
	private static final double RAMP_SECONDS = 0.005D;
	/** Ringback cadence: {@link #RINGBACK_ON_TICKS} of tone in every {@link #RINGBACK_CYCLE_TICKS}. */
	public static final int RINGBACK_CYCLE_TICKS = 120;
	public static final int RINGBACK_ON_TICKS = 40;

	/** Dial tone (350 + 440 Hz) for {@code seconds}: armed, key in a number. */
	public static List<short[]> dialTone(double seconds) {
		return pattern(350.0D, 440.0D, seconds, 0.0D, 1);
	}

	/** One ring of ringback (440 + 480 Hz, 2 s): the number called is ringing. */
	public static List<short[]> ringback() {
		return pattern(440.0D, 480.0D, RINGBACK_ON_TICKS / 20.0D, 0.0D, 1);
	}

	/** A short double ring (440 + 480 Hz) on the air before an incoming call is put through. */
	public static List<short[]> ringBurst() {
		return pattern(440.0D, 480.0D, 0.4D, 0.2D, 2);
	}

	/** Reorder ("fast busy", 480 + 620 Hz, a quarter second on and off, four times): the call could not be made. */
	public static List<short[]> reorder() {
		return pattern(480.0D, 620.0D, 0.25D, 0.25D, 4);
	}

	/** One beep: a call is up. */
	public static List<short[]> connected() {
		return RepeaterAudio.tone(1200.0D, 0.12D, LEVEL);
	}

	/** Two falling beeps: the call ended or the dialling was cancelled. */
	public static List<short[]> ended() {
		int beep = (int)(0.12D * SAMPLE_RATE), gap = (int)(0.08D * SAMPLE_RATE);
		double[] samples = new double[beep * 2 + gap];
		tone(samples, 0, beep, 1000.0D, 0.0D);
		tone(samples, beep + gap, beep, 600.0D, 0.0D);
		return RepeaterAudio.frames(samples);
	}

	/** How many game ticks {@code frames} take to send (2.5 frames a tick). */
	public static int ticks(List<short[]> frames) {
		return (int)Math.ceil(frames.size() / 2.5D);
	}

	/** {@code count} bursts of tones {@code a} and {@code b} ({@code b} 0 for one tone), {@code on} seconds each, {@code off} apart. */
	private static List<short[]> pattern(double a, double b, double on, double off, int count) {
		int onSamples = (int)(on * SAMPLE_RATE), offSamples = (int)(off * SAMPLE_RATE);
		double[] samples = new double[count * onSamples + (count - 1) * offSamples];
		for(int i = 0; i < count; i++)
			tone(samples, i * (onSamples + offSamples), onSamples, a, b);
		return RepeaterAudio.frames(samples);
	}

	private static void tone(double[] samples, int from, int length, double a, double b) {
		int ramp = (int)(RAMP_SECONDS * SAMPLE_RATE);
		for(int i = 0; i < length && from + i < samples.length; i++) {
			double envelope = Math.min(1.0D, Math.min(i, length - 1 - i) / (double)ramp);
			double t = i / SAMPLE_RATE;
			double x = Math.sin(2.0D * Math.PI * a * t) + (b > 0.0D ? Math.sin(2.0D * Math.PI * b * t) : 0.0D);
			samples[from + i] = x * LEVEL * envelope;
		}
	}
}
