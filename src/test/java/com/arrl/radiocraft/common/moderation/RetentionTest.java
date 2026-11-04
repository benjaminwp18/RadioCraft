package com.arrl.radiocraft.common.moderation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which whole files retention and the storage cap delete. */
class RetentionTest {

	private static final long HOUR = 3_600_000L;

	private static List<Retention.FileSpan> hours(long from, int count, long bytes) {
		List<Retention.FileSpan> out = new ArrayList<>();
		for(int i = 0; i < count; i++)
			out.add(new Retention.FileSpan("h" + i, from + i * HOUR, HOUR, bytes));
		return out;
	}

	@Test
	void onlyFilesWhollyPastTheCutoffGo() {
		List<Retention.FileSpan> files = hours(0L, 5, 10L);
		List<Retention.FileSpan> gone = Retention.expired(files, 2 * HOUR + 1);
		assertEquals(List.of("h0", "h1"), gone.stream().map(Retention.FileSpan::name).toList(), "h2 still holds frames after the cutoff");
	}

	@Test
	void theCapTakesTheOldestFirstButNeverTheHourBeingWritten() {
		List<Retention.FileSpan> files = new ArrayList<>(hours(0L, 4, 100L));
		java.util.Collections.reverse(files); // Order given does not matter.
		List<Retention.FileSpan> gone = Retention.overCap(files, 50L, 250L, 3 * HOUR + 10);
		assertEquals(List.of("h0", "h1"), gone.stream().map(Retention.FileSpan::name).toList());
		List<Retention.FileSpan> all = Retention.overCap(hours(0L, 2, 1000L), 0L, 0L, HOUR + 5);
		assertEquals(List.of("h0"), all.stream().map(Retention.FileSpan::name).toList(), "h1 is being written");
		assertTrue(Retention.overCap(files, 0L, 10_000L, 0L).isEmpty());
	}
}
