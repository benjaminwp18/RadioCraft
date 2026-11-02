package com.arrl.radiocraft.common.radio.voice;

/**
 * How a receiver's AGC balances the wanted signal against the noise. A real receiver sets its gain from the total power
 * coming in (signal plus noise) and holds the output steady, so a strong signal turns the gain down and the background
 * noise drops away under it, while a weak one leaves the gain up and the noise sits on top of it. Amplitudes are 0 to 1:
 * {@code signal} is the received strength, {@code noise} the noise floor (solar, redstone, lightning, aurora).
 */
public final class ReceiverMix {

	private ReceiverMix() {}

	/** How much of a weak signal's loss of level is heard: the share of signal in the total, to this power. */
	static final double VOICE_LEVEL_POWER = 0.3D;

	/**
	 * Output level of the wanted signal: about 1 when it is well above the noise. A weak voice mostly stays up and the
	 * static stays with it ({@link #noiseDuck}), as an AGC holds the speaker steady (maintainer, 2026-11-02): level 0.9 at
	 * the noise, 0.5 at a tenth of it, sinking away only when it is buried.
	 */
	public static double voiceLevel(double signal, double noise) {
		if(signal <= 0.0D)
			return 0.0D;
		return Math.pow(signal / Math.sqrt(signal * signal + noise * noise), VOICE_LEVEL_POWER);
	}

	/**
	 * Factor the background static is multiplied by: 1 with no signal (the static plays at the noise floor, as always),
	 * falling toward 0 as the signal rises above the noise.
	 */
	public static double noiseDuck(double signal, double noise) {
		if(signal <= 0.0D)
			return 1.0D;
		if(noise <= 0.0D)
			return 0.0D;
		return noise / Math.sqrt(signal * signal + noise * noise);
	}

	/** Blocks at which a station counts half as near in a mix ({@link #nearness}). */
	static final double NEAR_BLOCKS = 16.0D;
	/** Blocks within which a transmitter starts to overload a receiver's front end, and the splatter it reaches. */
	static final double OVERLOAD_BLOCKS = 4.0D;
	static final double OVERLOAD_SPLATTER = 0.05D;

	/**
	 * How much nearer a station is, 1 close by and falling with distance, for weighting it in a mix only. Propagation
	 * falls in a straight line over a band's whole range, so 1 and 20 blocks come out nearly equal and an antenna's gain
	 * outweighs them; a real signal 20 blocks off is far below one beside you (maintainer, 2026-11-02).
	 */
	public static double nearness(double blocks) {
		return 1.0D / (1.0D + Math.max(0.0D, blocks) / NEAR_BLOCKS);
	}

	/** A little roughness on a transmitter just beside the receiver: splatter, 0 from {@link #OVERLOAD_BLOCKS} out. */
	public static double overload(double blocks) {
		return OVERLOAD_SPLATTER * Math.max(0.0D, 1.0D - blocks / OVERLOAD_BLOCKS);
	}

	/**
	 * Share of a received signal left with a transmitter {@code blocks} from the antenna, {@code offsetHz} away: a nearby
	 * transmitter overloads a receiver's front end even off frequency (maintainer, 2026-11-02). 1 from {@code range}
	 * blocks or {@code separationHz} out, scaling down to {@code 1 - depth} right beside it on frequency.
	 */
	public static double desense(double blocks, double offsetHz, double range, double separationHz, double depth) {
		double near = range <= 0.0D ? 0.0D : Math.max(0.0D, 1.0D - blocks / range);
		double close = Math.max(0.0D, 1.0D - Math.abs(offsetHz) / separationHz);
		return 1.0D - depth * near * close;
	}

	/** Signal strength from which an FM receiver is fully quiet (full quieting): no hiss under the voice. */
	public static final double FM_QUIETING = 0.3D;
	/** Below this an FM signal starts to cut in and out (picket fencing). */
	public static final double FM_DROPOUT_START = 0.15D;
	/** Below this the voice is gone: the squelch is open on static alone. */
	public static final double FM_STATIC_ONLY = 0.04D;

	/**
	 * The hiss under an FM signal, 0 to 1: none on a signal at {@link #FM_QUIETING} or above, rising as it weakens.
	 * FM does not get quieter as the signal fades, only noisier; the voice stays at full volume.
	 */
	public static double fmHiss(double signal) {
		if(signal <= 0.0D)
			return 1.0D;
		if(signal >= FM_QUIETING)
			return 0.0D;
		double below = 1.0D - signal / FM_QUIETING;
		return below * below;
	}

	/**
	 * Share of the time a weak FM signal drops out to static, 0 to 1: none above {@link #FM_DROPOUT_START}, all of it
	 * below {@link #FM_STATIC_ONLY}. Past that the signal is out of reach and the squelch shuts: silence.
	 */
	public static double fmDropout(double signal) {
		return Math.max(0.0D, Math.min(1.0D, (FM_DROPOUT_START - signal) / (FM_DROPOUT_START - FM_STATIC_ONLY)));
	}

	/**
	 * Slow fading (QSB) on a skip signal: a factor between {@code 1 - depth} and 1 that swells and sinks over several
	 * seconds, from two slow waves so it never quite repeats. {@code seed} makes each path fade on its own.
	 */
	public static double fading(double seconds, long seed, double depth) {
		double phase = (seed & 0xFFFF) / 65536.0D * Math.PI * 2.0D;
		double wave = 0.6D * Math.sin(seconds * 0.71D + phase) + 0.4D * Math.sin(seconds * 0.29D + phase * 1.7D);
		return 1.0D - depth * (0.5D + 0.5D * wave);
	}
}
