package com.arrl.radiocraft.common.moderation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Small pure helpers the moderation computer shares between its server side and its screen: the time range presets
 * (worked out on the server, with the server's clock and time zone), kinds packed as a bit mask for a request, and how
 * frequencies, lengths, sizes and times are written. No game classes.
 */
public final class ModerationFormat {

	private ModerationFormat() {}

	private static final long HOUR_MS = 3_600_000L;
	private static final long DAY_MS = 24L * HOUR_MS;

	/** A time range offered on the computer's screen. */
	public enum Range {
		LAST_HOUR, TODAY, LAST_24_HOURS, LAST_7_DAYS, ALL;

		/** Lower case name, the key of its label. */
		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		/** The range at {@code ordinal}, or {@link #LAST_24_HOURS} for one out of range. */
		public static Range byOrdinal(int ordinal) {
			Range[] all = values();
			return ordinal >= 0 && ordinal < all.length ? all[ordinal] : LAST_24_HOURS;
		}
	}

	/**
	 * {@code range} as epoch ms {@code {from (inclusive), to (exclusive)}} at {@code nowMs}: today starts at midnight in
	 * {@code zone}; "to" is a minute ahead of now, so a transmission closing as the request comes in is not cut off.
	 */
	public static long[] range(Range range, long nowMs, ZoneId zone) {
		long to = nowMs + 60_000L;
		long from = switch(range) {
			case LAST_HOUR -> nowMs - HOUR_MS;
			case TODAY -> LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone).atStartOfDay(zone).toInstant().toEpochMilli();
			case LAST_24_HOURS -> nowMs - DAY_MS;
			case LAST_7_DAYS -> nowMs - 7L * DAY_MS;
			case ALL -> 0L;
		};
		return new long[] { from, to };
	}

	/** {@code kinds} as a bit mask (bit = ordinal); empty is 0. */
	public static int mask(Set<TransmissionKind> kinds) {
		int mask = 0;
		for(TransmissionKind kind : kinds)
			mask |= 1 << kind.ordinal();
		return mask;
	}

	/** The kinds in {@code mask}; empty for 0. */
	public static Set<TransmissionKind> kinds(int mask) {
		Set<TransmissionKind> out = EnumSet.noneOf(TransmissionKind.class);
		for(TransmissionKind kind : TransmissionKind.values())
			if((mask & 1 << kind.ordinal()) != 0)
				out.add(kind);
		return out;
	}

	/** A frequency in MHz to 4 places ("146.5200"), or "-" for none (proximity). */
	public static String mhz(float hz) {
		return hz <= 0.0F ? "-" : String.format(Locale.ROOT, "%.4f", hz / 1_000_000.0D);
	}

	/**
	 * A frequency typed in MHz ("146.52", "7.2") as Hz, or NaN if it is not a number. Blank is NaN too: the caller
	 * leaves that end of the range open.
	 */
	public static float parseMhz(String text) {
		String trimmed = text == null ? "" : text.trim().replace(',', '.');
		if(trimmed.isEmpty())
			return Float.NaN;
		try {
			double mhz = Double.parseDouble(trimmed);
			return mhz < 0.0D || Double.isNaN(mhz) || Double.isInfinite(mhz) ? Float.NaN : (float)(mhz * 1_000_000.0D);
		}
		catch(NumberFormatException e) {
			return Float.NaN;
		}
	}

	/** A length as m:ss, or h:mm:ss from an hour up. */
	public static String duration(long ms) {
		long seconds = Math.max(0L, ms) / 1000L;
		long h = seconds / 3600L, m = seconds / 60L % 60L, s = seconds % 60L;
		return h > 0L ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
	}

	/** A size in B, KB, MB or GB (1024s), one decimal from KB up. */
	public static String bytes(long bytes) {
		if(bytes < 1024L)
			return Math.max(0L, bytes) + " B";
		String[] units = { "KB", "MB", "GB", "TB" };
		double value = bytes;
		int unit = -1;
		while(value >= 1024.0D && unit < units.length - 1) {
			value /= 1024.0D;
			unit++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	/** A position as "x, y, z". */
	public static String pos(int x, int y, int z) {
		return x + ", " + y + ", " + z;
	}

	/** A position typed as "x, y, z" or "x y z" ({@code {x, y, z}}), or null if it is not three whole numbers. */
	public static int[] parsePos(String text) {
		if(text == null)
			return null;
		String[] parts = text.trim().split("[,\\s]+");
		if(parts.length != 3)
			return null;
		int[] out = new int[3];
		try {
			for(int i = 0; i < 3; i++)
				out[i] = Integer.parseInt(parts[i]);
		}
		catch(NumberFormatException e) {
			return null;
		}
		return out;
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

	/** An epoch ms time as "MM-dd HH:mm:ss" in {@code zone}; "-" for 0. */
	public static String time(long ms, ZoneId zone) {
		return ms <= 0L ? "-" : TIME.format(Instant.ofEpochMilli(ms).atZone(zone));
	}

	/** How long ago {@code ms} was at {@code nowMs}: "5 s", "3 min", "2 h", "4 d". */
	public static String ago(long ms, long nowMs) {
		long seconds = Math.max(0L, nowMs - ms) / 1000L;
		if(seconds < 60L)
			return seconds + " s";
		if(seconds < 3600L)
			return seconds / 60L + " min";
		if(seconds < 86400L)
			return seconds / 3600L + " h";
		return seconds / 86400L + " d";
	}

	/** {@code text} cut to {@code max} characters, an ellipsis at the end if it was longer. */
	public static String clip(String text, int max) {
		if(text == null)
			return "";
		return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
	}
}
