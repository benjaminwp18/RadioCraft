package com.arrl.radiocraft.client.screens.moderation;

/**
 * The moderation computer screen's layout arithmetic, kept pure so it can be unit tested: how far to shrink the screen
 * to fit the window, column widths, list scrolling and scrollbar thumbs, the link graph's circle and line widths, and
 * fitting the map to the radios on it. No game classes.
 */
public final class ModerationLayout {

	private ModerationLayout() {}

	/**
	 * How much to shrink the screen (1 = not at all) so a layout needing {@code minW} x {@code minH} GUI pixels fits a
	 * window of {@code guiW} x {@code guiH}: at GUI scale 4 on a small window everything is drawn smaller, never larger.
	 */
	public static float fitScale(int guiW, int guiH, int minW, int minH) {
		return Math.min(1.0F, Math.min((float)guiW / minW, (float)guiH / minH));
	}

	/**
	 * {@code total} pixels shared between columns by {@code weights}, each at least {@code min} where there is room; the
	 * last column takes what rounding leaves, so the widths add up to {@code total}.
	 */
	public static int[] columns(int total, int[] weights, int min) {
		int[] out = new int[weights.length];
		if(weights.length == 0)
			return out;
		int sum = 0;
		for(int weight : weights)
			sum += Math.max(0, weight);
		int used = 0;
		for(int i = 0; i < weights.length - 1; i++) {
			int width = sum == 0 ? total / weights.length : (int)((long)total * Math.max(0, weights[i]) / sum);
			if(min * weights.length <= total)
				width = Math.max(min, width);
			out[i] = width;
			used += width;
		}
		out[weights.length - 1] = Math.max(0, total - used);
		return out;
	}

	/** The first row to show of {@code count}, {@code visible} at a time, kept in range. */
	public static int clampFirst(int first, int visible, int count) {
		return Math.max(0, Math.min(first, count - Math.max(1, visible)));
	}

	/** The first row to show so that {@code selected} is in view, moving the list as little as possible. */
	public static int scrollTo(int selected, int first, int visible, int count) {
		if(selected < 0)
			return clampFirst(first, visible, count);
		if(selected < first)
			first = selected;
		else if(selected >= first + visible)
			first = selected - visible + 1;
		return clampFirst(first, visible, count);
	}

	/**
	 * A scrollbar thumb on a track of {@code track} pixels for a list showing {@code visible} of {@code count} rows from
	 * {@code first}: {@code {offset, length}}, the length at least {@code minThumb}. The whole track when all fit.
	 */
	public static int[] thumb(int track, int first, int visible, int count, int minThumb) {
		if(count <= visible || count <= 0)
			return new int[] { 0, track };
		int length = Math.max(Math.min(minThumb, track), (int)((long)track * visible / count));
		int room = track - length;
		int maxFirst = count - visible;
		int offset = maxFirst <= 0 ? 0 : (int)Math.round((double)room * Math.min(first, maxFirst) / maxFirst);
		return new int[] { offset, length };
	}

	/** The first row for a thumb dragged to {@code offset} (see {@link #thumb}). */
	public static int firstForThumb(int offset, int track, int visible, int count, int minThumb) {
		int[] thumb = thumb(track, 0, visible, count, minThumb);
		int room = track - thumb[1];
		if(room <= 0)
			return 0;
		return clampFirst((int)Math.round((double)Math.max(0, Math.min(room, offset)) / room * (count - visible)), visible, count);
	}

	/** {@code n} points evenly on a circle of radius {@code r} round ({@code cx}, {@code cy}), the first at the top, going clockwise. */
	public static double[][] circle(int n, double cx, double cy, double r) {
		double[][] out = new double[Math.max(0, n)][2];
		for(int i = 0; i < n; i++) {
			double angle = 2.0D * Math.PI * i / n - Math.PI / 2.0D;
			out[i][0] = cx + Math.cos(angle) * r;
			out[i][1] = cy + Math.sin(angle) * r;
		}
		return out;
	}

	/** A link line's width, 1 to 4 pixels, by its count against the busiest link's. */
	public static int thickness(int count, int max) {
		if(max <= 0 || count <= 0)
			return 1;
		return Math.max(1, Math.min(4, 1 + (int)Math.round(3.0D * count / max)));
	}

	/** Where the map looks: the block at its middle, and blocks per screen pixel. */
	public record View(double centerX, double centerZ, double blocksPerPixel) {

		/** A screen x for block x, the map being {@code width} wide from {@code left}. */
		public double screenX(double x, int left, int width) {
			return left + width / 2.0D + (x - centerX) / blocksPerPixel;
		}

		public double screenY(double z, int top, int height) {
			return top + height / 2.0D + (z - centerZ) / blocksPerPixel;
		}

		public View zoomed(double factor) {
			return new View(centerX, centerZ, Math.max(MIN_BLOCKS_PER_PIXEL, Math.min(MAX_BLOCKS_PER_PIXEL, blocksPerPixel * factor)));
		}

		public View panned(double dxPixels, double dyPixels) {
			return new View(centerX - dxPixels * blocksPerPixel, centerZ - dyPixels * blocksPerPixel, blocksPerPixel);
		}
	}

	public static final double MIN_BLOCKS_PER_PIXEL = 0.25D, MAX_BLOCKS_PER_PIXEL = 512.0D;

	/**
	 * A view showing every point ({@code xs[i]}, {@code zs[i]}) on a map {@code width} x {@code height} pixels, with a
	 * margin; one point, or none, is shown close up (round 0, 0 for none).
	 */
	public static View fit(int[] xs, int[] zs, int width, int height) {
		int n = Math.min(xs.length, zs.length);
		if(n == 0)
			return new View(0.0D, 0.0D, 1.0D);
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for(int i = 0; i < n; i++) {
			minX = Math.min(minX, xs[i]);
			maxX = Math.max(maxX, xs[i]);
			minZ = Math.min(minZ, zs[i]);
			maxZ = Math.max(maxZ, zs[i]);
		}
		double spanX = Math.max(32.0D, maxX - minX), spanZ = Math.max(32.0D, maxZ - minZ);
		double perPixel = Math.max(spanX / Math.max(1, width), spanZ / Math.max(1, height)) * 1.2D;
		return new View((minX + maxX) / 2.0D + 0.5D, (minZ + maxZ) / 2.0D + 0.5D, Math.max(MIN_BLOCKS_PER_PIXEL, Math.min(MAX_BLOCKS_PER_PIXEL, perPixel)));
	}

	/**
	 * Orders two sort keys from a list's column: numbers by value, anything else as text ignoring case; a missing key
	 * (null) after everything.
	 */
	public static int compareKeys(Object a, Object b) {
		if(a == null || b == null)
			return a == null ? (b == null ? 0 : 1) : -1;
		if(a instanceof Number x && b instanceof Number y)
			return Double.compare(x.doubleValue(), y.doubleValue());
		return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
	}
}
