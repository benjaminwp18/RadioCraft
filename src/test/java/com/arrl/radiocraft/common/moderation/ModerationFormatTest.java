package com.arrl.radiocraft.common.moderation;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The moderation computer's time ranges, kind masks and how it writes and reads numbers. */
class ModerationFormatTest {

	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final long NOW = LocalDateTime.of(2026, 11, 4, 15, 30).toInstant(ZoneOffset.UTC).toEpochMilli();

	@Test
	void rangesEndJustAfterNowAndStartWhereTheySay() {
		long[] hour = ModerationFormat.range(ModerationFormat.Range.LAST_HOUR, NOW, UTC);
		assertEquals(NOW - 3_600_000L, hour[0]);
		assertTrue(hour[1] > NOW, "a transmission closing now is inside the range");
		long[] today = ModerationFormat.range(ModerationFormat.Range.TODAY, NOW, UTC);
		assertEquals(LocalDateTime.of(2026, 11, 4, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), today[0]);
		assertEquals(NOW - 7L * 86_400_000L, ModerationFormat.range(ModerationFormat.Range.LAST_7_DAYS, NOW, UTC)[0]);
		assertEquals(0L, ModerationFormat.range(ModerationFormat.Range.ALL, NOW, UTC)[0]);
		assertEquals(ModerationFormat.Range.LAST_24_HOURS, ModerationFormat.Range.byOrdinal(99), "an unknown range falls back to a day");
	}

	@Test
	void kindMasksRoundTrip() {
		Set<TransmissionKind> kinds = EnumSet.of(TransmissionKind.PLAYER_VOICE, TransmissionKind.CW, TransmissionKind.DATA);
		assertEquals(kinds, ModerationFormat.kinds(ModerationFormat.mask(kinds)));
		assertEquals(0, ModerationFormat.mask(Set.of()));
		assertTrue(ModerationFormat.kinds(0).isEmpty());
	}

	@Test
	void numbersAreWrittenPlainly() {
		assertEquals("146.5200", ModerationFormat.mhz(146_520_000.0F));
		assertEquals("-", ModerationFormat.mhz(0.0F), "proximity has no frequency");
		assertEquals("0:05", ModerationFormat.duration(5_400L));
		assertEquals("1:02:03", ModerationFormat.duration(3_723_000L));
		assertEquals("512 B", ModerationFormat.bytes(512L));
		assertEquals("1.5 MB", ModerationFormat.bytes(1_572_864L));
		assertEquals("5.0 GB", ModerationFormat.bytes(5L * 1024 * 1024 * 1024));
		assertEquals("11-04 15:30:00", ModerationFormat.time(NOW, UTC));
		assertEquals("-", ModerationFormat.time(0L, UTC));
		assertEquals("3 min", ModerationFormat.ago(NOW - 200_000L, NOW));
		assertEquals("ab…", ModerationFormat.clip("abcdef", 3));
	}

	@Test
	void typedValuesAreRead() {
		assertEquals(146_520_000.0F, ModerationFormat.parseMhz(" 146.52 "), 1.0F);
		assertEquals(7_200_000.0F, ModerationFormat.parseMhz("7,2"), 1.0F);
		assertTrue(Float.isNaN(ModerationFormat.parseMhz("")), "blank leaves the range open");
		assertTrue(Float.isNaN(ModerationFormat.parseMhz("abc")));
		assertTrue(Float.isNaN(ModerationFormat.parseMhz("-3")));
		assertArrayEquals(new int[] { 10, 64, -20 }, ModerationFormat.parsePos("10, 64, -20"));
		assertArrayEquals(new int[] { 1, 2, 3 }, ModerationFormat.parsePos("1 2 3"));
		assertNull(ModerationFormat.parsePos("1, 2"));
		assertNull(ModerationFormat.parsePos("1, x, 3"));
	}
}
