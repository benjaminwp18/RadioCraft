package com.arrl.radiocraft.client.screens.moderation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The moderation computer screen's layout arithmetic: fitting the window, columns, scrolling, the graph and the map. */
class ModerationLayoutTest {

	@Test
	void shrinksOnlyWhenTheWindowIsTooSmall() {
		assertEquals(1.0F, ModerationLayout.fitScale(960, 540, 428, 258), "GUI scale 2 on 1080p fits");
		assertEquals(1.0F, ModerationLayout.fitScale(480, 270, 428, 258), "GUI scale 4 on 1080p fits");
		float small = ModerationLayout.fitScale(320, 180, 428, 258); // GUI scale 4 on 720p.
		assertTrue(small < 1.0F && 428 * small <= 320.5F && 258 * small <= 180.5F, "shrunk to fit: " + small);
	}

	@Test
	void columnsAddUpToTheWidth() {
		int[] widths = ModerationLayout.columns(301, new int[] { 2, 1, 1 }, 16);
		assertEquals(301, widths[0] + widths[1] + widths[2]);
		assertEquals(150, widths[0]);
		int[] narrow = ModerationLayout.columns(100, new int[] { 10, 1, 1 }, 16);
		assertTrue(narrow[1] >= 16 && narrow[2] >= 0 && narrow[0] + narrow[1] + narrow[2] == 100, "minimum widths kept");
	}

	@Test
	void scrollingKeepsTheSelectionInView() {
		assertEquals(0, ModerationLayout.clampFirst(-3, 10, 50));
		assertEquals(40, ModerationLayout.clampFirst(99, 10, 50));
		assertEquals(0, ModerationLayout.clampFirst(5, 10, 4), "everything fits");
		assertEquals(16, ModerationLayout.scrollTo(25, 0, 10, 50));
		assertEquals(3, ModerationLayout.scrollTo(3, 10, 10, 50));
		assertEquals(12, ModerationLayout.scrollTo(15, 12, 10, 50), "already in view: no move");
	}

	@Test
	void scrollbarThumbFollowsTheList() {
		assertArrayEquals(new int[] { 0, 100 }, ModerationLayout.thumb(100, 0, 10, 5, 8), "all rows fit: the whole track");
		int[] top = ModerationLayout.thumb(100, 0, 10, 100, 8), bottom = ModerationLayout.thumb(100, 90, 10, 100, 8);
		assertEquals(0, top[0]);
		assertEquals(10, top[1]);
		assertEquals(100, bottom[0] + bottom[1]);
		assertEquals(8, ModerationLayout.thumb(100, 0, 1, 1000, 8)[1], "never thinner than the minimum");
		assertEquals(90, ModerationLayout.firstForThumb(90, 100, 10, 100, 8));
		assertEquals(45, ModerationLayout.firstForThumb(45, 100, 10, 100, 8));
	}

	@Test
	void graphCircleAndLineWidths() {
		double[][] points = ModerationLayout.circle(4, 50.0D, 50.0D, 10.0D);
		assertEquals(50.0D, points[0][0], 1e-9);
		assertEquals(40.0D, points[0][1], 1e-9, "the first node is at the top");
		assertEquals(60.0D, points[1][0], 1e-9, "then clockwise");
		assertEquals(1, ModerationLayout.thickness(1, 100));
		assertEquals(4, ModerationLayout.thickness(100, 100));
		assertEquals(1, ModerationLayout.thickness(0, 0));
	}

	@Test
	void mapFitsEveryRadio() {
		int[] xs = { -100, 300 }, zs = { 0, 50 };
		ModerationLayout.View view = ModerationLayout.fit(xs, zs, 200, 100);
		for(int i = 0; i < xs.length; i++) {
			double sx = view.screenX(xs[i], 0, 200), sy = view.screenY(zs[i], 0, 100);
			assertTrue(sx >= 0 && sx <= 200 && sy >= 0 && sy <= 100, "radio " + i + " off the map at " + sx + ", " + sy);
		}
		ModerationLayout.View one = ModerationLayout.fit(new int[] { 7 }, new int[] { 9 }, 200, 100);
		assertTrue(one.blocksPerPixel() < 1.0D, "one radio is shown close up");
		assertEquals(view.blocksPerPixel() * 2.0D, view.zoomed(2.0D).blocksPerPixel(), 1e-9);
		assertEquals(view.centerX() - 10.0D * view.blocksPerPixel(), view.panned(10.0D, 0.0D).centerX(), 1e-9);
	}

	/** Sort keys: numbers by value (so 9 before 10), text ignoring case, missing keys last. */
	@Test
	void sortKeysCompare() {
		assertTrue(ModerationLayout.compareKeys(9, 10L) < 0);
		assertTrue(ModerationLayout.compareKeys(146.52F, 28.4D) > 0);
		assertTrue(ModerationLayout.compareKeys("alice", "Bob") < 0);
		assertTrue(ModerationLayout.compareKeys(null, "a") > 0);
		assertEquals(0, ModerationLayout.compareKeys(null, null));
	}
}
