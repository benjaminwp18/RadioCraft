package com.arrl.radiocraft.common.radio.voice;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Two or more stations heard on one receiver at the same time. Each arrives as its own stream of 20 ms frames; a
 * receiver plays one stream, so they are lined up ({@link Aligner}) and combined into one frame ({@link Renderer}):
 * <ul>
 *     <li>SSB (and anything not FM) adds up: both voices are heard over each other, the stronger on air a little louder
 *     ({@link #ssbWeights}), each a few Hz off the other ({@link #ssbOffsetHz}), under one AGC that lets the weaker come
 *     up in the stronger's gaps.</li>
 *     <li>FM captures the stronger signal: once it is {@link #FM_CAPTURE_RATIO} times the next, only it is heard, roughened
 *     the closer the other comes. Closer than that, neither captures: the receiver flips between them in garbled bursts
 *     (doubling).</li>
 * </ul>
 * One station alone goes through untouched, with no added delay.
 */
public final class VoiceMix {

	private VoiceMix() {}

	/** The weaker FM signal is captured (shut out) below this share of the stronger one's strength. */
	public static final double FM_CAPTURE_RATIO = 0.5D;
	/** Garble on a captured FM signal as the other comes up to the capture ratio (splatter, 0 to 1). */
	static final double FM_CAPTURE_GARBLE = 0.3D;
	/** Level the captured-out signal bleeds through at, just under the capture ratio. */
	static final double FM_CAPTURE_BLEED = 0.25D;
	/** Garble and bleed while two FM signals double. */
	static final double FM_DOUBLING_GARBLE = 0.5D;
	static final double FM_DOUBLING_BLEED = 0.45D;
	/** How sharply an SSB voice drops behind the strongest in a mix, as the power of their ratio on air. */
	static final double SSB_MIX_POWER = 2.0D;
	/** The least an SSB voice is weighted against the strongest (about 4.4 dB down), so both are really heard. */
	static final double SSB_MIX_FLOOR = 0.6D;
	/** What the shared AGC holds a mix's envelope at, as a share of the loudest station's level. */
	static final double MIX_AGC_TARGET = 0.3D;
	/** How far the shared AGC moves: down under two voices at once, up in the gaps. */
	static final double MIX_AGC_MIN = 0.6D, MIX_AGC_MAX = 1.6D;
	private static final double MIX_AGC_GATE = 0.01D;
	private static final double MIX_AGC_ATTACK = 0.005D, MIX_AGC_RELEASE = 0.15D;
	/** How far off zero beat a station in a mix sits, Hz either way. */
	static final double SSB_OFFSET_MIN_HZ = 10.0D, SSB_OFFSET_MAX_HZ = 40.0D;

	/**
	 * One station's frame as the receiver heard it: the parameters of {@link RadioVoiceProcessor#process} and, for FM,
	 * {@link RadioVoiceProcessor#fmFade}.
	 *
	 * @param key      Who it is: the talker and the antenna it came from (a parrot echoing you is not you).
	 * @param source   The talking player, for the squelch tail; may be null.
	 * @param fmSignal The signal's strength on an FM receiver, below 0 for SSB/AM.
	 * @param onAir    How strong it counts in a mix, whatever the noise did to {@code level}: ranks and weights it.
	 * @param multipath How strong its late sky path gets, 0 to 1.
	 */
	public record Signal(Object key, @Nullable UUID source, short[] pcm, double level, double rasp, double pitchShiftHz,
						 double splatter, double qrm, double fmSignal, double onAir, double multipath) {
		/** With its strength on air taken from {@code fmSignal} or, for SSB, {@code level}. */
		public Signal(Object key, @Nullable UUID source, short[] pcm, double level, double rasp, double pitchShiftHz,
					  double splatter, double qrm, double fmSignal) {
			this(key, source, pcm, level, rasp, pitchShiftHz, splatter, qrm, fmSignal, fmSignal >= 0.0D ? fmSignal : level, 0.0D);
		}

		public boolean fm() {
			return fmSignal >= 0.0D;
		}

		/** How strong it is on air, to rank it against the others. */
		double strength() {
			return fm() ? fmSignal : onAir;
		}
	}

	/**
	 * Each SSB voice's weight in a mix, in the order of {@code strengths}: a second voice brings the first down so the sum
	 * keeps one voice's power, and the weaker sits a little under the stronger, compressed from the real difference so
	 * neither is lost (maintainer, 2026-11-02).
	 */
	static double[] ssbWeights(double[] strengths) {
		double strongest = 0.0D;
		for(double strength : strengths)
			strongest = Math.max(strongest, strength);
		double[] out = new double[strengths.length];
		double power = 0.0D;
		for(int i = 0; i < out.length; i++) {
			out[i] = strongest <= 0.0D ? 1.0D : Math.max(SSB_MIX_FLOOR, Math.pow(strengths[i] / strongest, SSB_MIX_POWER));
			power += out[i] * out[i];
		}
		for(int i = 0; i < out.length; i++)
			out[i] /= Math.sqrt(power);
		return out;
	}

	/**
	 * How far off zero beat {@code key}'s station sits while mixed, from who it is so it stays put: real stations are
	 * rarely on exactly one frequency, and the difference helps the ear pull two voices apart.
	 */
	static double ssbOffsetHz(Object key) {
		int hash = key.hashCode() * 0x9E3779B1;
		double offset = SSB_OFFSET_MIN_HZ + (SSB_OFFSET_MAX_HZ - SSB_OFFSET_MIN_HZ) * ((hash >>> 8 & 0xFF) / 255.0D);
		return (hash & 0x80000000) == 0 ? offset : -offset;
	}

	/** The key for a talker heard through an antenna. */
	public static Object key(@Nullable UUID player, @Nullable Object antenna) {
		return new Key(player, antenna == null ? 0 : System.identityHashCode(antenna));
	}

	private record Key(@Nullable UUID player, int antenna) {}

	/**
	 * Lines up frames from stations talking at once. The first station to talk leads: each of its frames goes out at
	 * once, with one waiting frame from each other station. The others' frames wait (a few at most) for the lead's next
	 * one. When the lead stops, the next station to send a frame leads. A station alone always leads, so it is never
	 * held back. Not thread safe: hold the receiver's lock.
	 */
	public static final class Aligner {
		/** The lead is given up after this long without a frame from it. */
		static final long LEAD_TIMEOUT_NANOS = 70_000_000L;
		/** A waiting station is forgotten after this long without a frame. */
		static final long FORGET_NANOS = 250_000_000L;
		/** Frames kept waiting per station; older ones are dropped (100 ms of delay at most, for a lead sending a tick's worth at once). */
		static final int MAX_WAITING = 5;

		private Object lead;
		private long leadLast;
		private final Map<Object, ArrayDeque<Signal>> waiting = new LinkedHashMap<>();
		private final Map<Object, Long> heard = new HashMap<>();

		/** {@code signal} arrived at {@code nanos}: the frames to play now, one per station, the lead first; maybe none. */
		public List<Signal> offer(Signal signal, long nanos) {
			Object key = signal.key();
			heard.put(key, nanos);
			heard.entrySet().removeIf(e -> nanos - e.getValue() > FORGET_NANOS);
			waiting.keySet().removeIf(k -> !heard.containsKey(k));
			if(lead != null && !lead.equals(key) && nanos - leadLast > LEAD_TIMEOUT_NANOS)
				lead = null;
			if(lead == null)
				lead = key;
			if(!lead.equals(key)) {
				ArrayDeque<Signal> queue = waiting.computeIfAbsent(key, k -> new ArrayDeque<>());
				queue.addLast(signal);
				while(queue.size() > MAX_WAITING)
					queue.removeFirst();
				return List.of();
			}
			leadLast = nanos;
			List<Signal> out = new ArrayList<>();
			ArrayDeque<Signal> own = waiting.get(key); // Frames it sent while it was waiting go first, in order.
			if(own != null && !own.isEmpty()) {
				own.addLast(signal);
				out.add(own.removeFirst());
			}
			else
				out.add(signal);
			for(Map.Entry<Object, ArrayDeque<Signal>> entry : waiting.entrySet())
				if(!entry.getKey().equals(key) && !entry.getValue().isEmpty())
					out.add(entry.getValue().removeFirst());
			return out;
		}
	}

	/** A frame of mixed audio and the station that dominates it (for the squelch tail and meters). */
	public record Mixed(short[] pcm, Signal loudest) {}

	/**
	 * Turns a set of simultaneous frames into the one frame a speaker plays. Each station keeps its own processor
	 * (filters, AGC and level smoothing carry from frame to frame), and FM keeps one more for the hiss of the mix.
	 * Not thread safe: hold the receiver's lock.
	 */
	public static final class Renderer {
		/** A station's processor is let go after this long unheard. */
		static final long IDLE_NANOS = 2_000_000_000L;

		private static final class Track {
			final RadioVoiceProcessor processor = new RadioVoiceProcessor();
			double weight = -1.0D;
			long lastNanos;
		}

		private final Map<Object, Track> tracks = new HashMap<>();
		private final RadioVoiceProcessor fm = new RadioVoiceProcessor();
		private Object doublingPick;
		private int doublingFramesLeft;
		private long random = System.nanoTime() | 1L;
		private double mixGain = 1.0D;
		private double mixEnvelope = 0.0D;

		/**
		 * Mixes {@code signals} (the input frames are left alone) for a speaker with {@code settings}, at
		 * {@code loudness} times each station's level.
		 */
		public Mixed render(List<Signal> signals, RadioVoiceProcessor.Settings settings, double loudness, long nanos) {
			tracks.values().removeIf(t -> nanos - t.lastNanos > IDLE_NANOS);
			if(signals.size() == 1) {
				Signal s = signals.getFirst();
				Track track = track(s, nanos);
				track.weight = 1.0D; // A mix that starts later glides down from its full weight.
				short[] pcm = track.processor.process(s.pcm().clone(), settings, s.level() * loudness, s.rasp(), s.pitchShiftHz(), s.splatter(), s.qrm(), s.multipath());
				if(s.fm())
					fm.fmFade(pcm, s.fmSignal(), s.level() * loudness, settings.smoothSeconds());
				settleMixGain(pcm);
				return new Mixed(pcm, s);
			}
			List<Signal> ranked = new ArrayList<>(signals);
			ranked.sort(Comparator.comparingDouble(Signal::strength).reversed());
			Signal strongest = ranked.getFirst();
			if(!strongest.fm()) { // SSB: everyone is heard, at the level its own path gives it and its weight in the mix.
				double[] sum = new double[strongest.pcm().length];
				double[] weights = ssbWeights(ranked.stream().mapToDouble(Signal::strength).toArray());
				boolean shaped = settings.enabled();
				double loudest = 0.0D;
				for(int i = 0; i < weights.length; i++) {
					Signal s = ranked.get(i);
					add(sum, s, s.pitchShiftHz() + (shaped ? ssbOffsetHz(s.key()) : 0.0D), s.splatter(), weights[i], settings, loudness, nanos);
					loudest = Math.max(loudest, s.level() * loudness);
				}
				if(shaped)
					mixAgc(sum, loudest);
				return new Mixed(limit(sum), strongest);
			}
			Signal on;
			double garble, bleed;
			double ratio = strongest.fmSignal() <= 0.0D ? 1.0D : ranked.get(1).fmSignal() / strongest.fmSignal();
			if(ratio <= FM_CAPTURE_RATIO) { // Captured: the strong one wins, roughened as the other comes closer.
				on = strongest;
				double closeness = ratio / FM_CAPTURE_RATIO;
				garble = FM_CAPTURE_GARBLE * closeness;
				bleed = FM_CAPTURE_BLEED * closeness * closeness;
				doublingPick = null;
			}
			else { // Doubling: neither captures, and the receiver jumps between them.
				on = doublingPick(ranked);
				garble = FM_DOUBLING_GARBLE;
				bleed = FM_DOUBLING_BLEED;
			}
			double[] sum = new double[on.pcm().length];
			for(Signal s : ranked)
				add(sum, s, s.pitchShiftHz(), s == on ? Math.max(s.splatter(), garble) : s.splatter(), s == on ? 1.0D : bleed, settings, loudness, nanos);
			short[] pcm = limit(sum);
			fm.fmFade(pcm, on.fmSignal(), on.level() * loudness, settings.smoothSeconds());
			return new Mixed(pcm, on);
		}

		/**
		 * The receiver's one AGC over everything it hears (each station's own processor stands in for its transmitter's
		 * leveller): quick down when voices stack up, and back up over 150 ms, so the weaker voice lifts in the stronger's
		 * gaps. {@code loudest} is the top station's output level.
		 */
		private void mixAgc(double[] sum, double loudest) {
			double attack = RadioVoiceProcessor.coefficient(MIX_AGC_ATTACK), release = RadioVoiceProcessor.coefficient(MIX_AGC_RELEASE);
			for(int i = 0; i < sum.length; i++) {
				double magnitude = Math.abs(sum[i]);
				mixEnvelope = magnitude > mixEnvelope ? attack * mixEnvelope + (1.0D - attack) * magnitude : release * mixEnvelope + (1.0D - release) * magnitude;
				double wanted = mixEnvelope > MIX_AGC_GATE * loudest
						? Math.max(MIX_AGC_MIN, Math.min(MIX_AGC_MAX, MIX_AGC_TARGET * loudest / mixEnvelope))
						: mixGain; // Holds through silence, as each station's does.
				mixGain = wanted < mixGain ? attack * mixGain + (1.0D - attack) * wanted : release * mixGain + (1.0D - release) * wanted;
				sum[i] *= mixGain;
			}
		}

		/** One station again: the shared AGC's gain glides back to 1, so its last value is not a step. */
		private void settleMixGain(short[] pcm) {
			if(mixGain == 1.0D)
				return;
			double glide = RadioVoiceProcessor.coefficient(0.02D);
			for(int i = 0; i < pcm.length; i++) {
				mixGain = glide * mixGain + (1.0D - glide);
				pcm[i] = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(pcm[i] * mixGain)));
			}
			if(Math.abs(mixGain - 1.0D) < 1e-3D)
				mixGain = 1.0D;
			mixEnvelope = 0.0D;
		}

		/** Who an FM receiver locks onto in doubling, for a few frames at a time: more often the stronger. */
		private Signal doublingPick(List<Signal> ranked) {
			Signal current = null;
			for(Signal s : ranked)
				if(Objects.equals(s.key(), doublingPick))
					current = s;
			if(current != null && --doublingFramesLeft > 0)
				return current;
			double total = 0.0D;
			for(Signal s : ranked)
				total += s.fmSignal() * s.fmSignal();
			double roll = nextRandom() * total;
			Signal pick = ranked.getFirst();
			for(Iterator<Signal> it = ranked.iterator(); it.hasNext(); ) {
				Signal s = it.next();
				roll -= s.fmSignal() * s.fmSignal();
				if(roll <= 0.0D || !it.hasNext()) {
					pick = s;
					break;
				}
			}
			doublingPick = pick.key();
			doublingFramesLeft = 2 + (int)(nextRandom() * 5.0D); // 40 to 120 ms.
			return pick;
		}

		private Track track(Signal s, long nanos) {
			Track track = tracks.computeIfAbsent(s.key(), k -> new Track());
			track.lastNanos = nanos;
			return track;
		}

		/** Processes one station's frame and adds it into {@code sum} at {@code weight}, which glides over 5 ms. */
		private void add(double[] sum, Signal s, double pitchShiftHz, double splatter, double weight, RadioVoiceProcessor.Settings settings, double loudness, long nanos) {
			Track track = track(s, nanos);
			short[] pcm = track.processor.process(s.pcm().clone(), settings, s.level() * loudness, s.rasp(), pitchShiftHz, splatter, s.qrm(), s.multipath());
			if(track.weight < 0.0D)
				track.weight = weight;
			double glide = RadioVoiceProcessor.coefficient(0.005D);
			for(int i = 0; i < Math.min(sum.length, pcm.length); i++) {
				track.weight = glide * track.weight + (1.0D - glide) * weight;
				sum[i] += pcm[i] / 32768.0D * track.weight;
			}
		}

		private double nextRandom() {
			random ^= random << 13;
			random ^= random >>> 7;
			random ^= random << 17;
			return (random >>> 11) * 0x1.0p-53;
		}
	}

	/** Two voices at full level add past full scale: a soft knee above 0.7 keeps the sum from hard clipping. */
	static short[] limit(double[] sum) {
		short[] out = new short[sum.length];
		for(int i = 0; i < sum.length; i++) {
			double x = sum[i], magnitude = Math.abs(x);
			if(magnitude > 0.7D)
				x = Math.signum(x) * (0.7D + 0.3D * RadioVoiceProcessor.softClip((magnitude - 0.7D) / 0.3D));
			out[i] = (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(x * 32767.0D)));
		}
		return out;
	}
}
