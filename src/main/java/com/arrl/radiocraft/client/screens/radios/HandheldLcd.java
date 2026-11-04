package com.arrl.radiocraft.client.screens.radios;

import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The handheld's monochrome LCD: a grey-green glass with dark pixels, drawn as a real one is built. Text is a 3x5
 * dot-matrix font (5 wide for M and W), numbers on the main line are seven-segment digits with their unlit segments
 * faintly showing, headings are inverted bars and the backlight comes on for a few seconds after a key.
 * Coordinates are in LCD pixels from its top left corner; it is {@link #WIDTH} by {@link #HEIGHT}.
 */
final class HandheldLcd {

	static final int WIDTH = 61;
	static final int HEIGHT = 28;
	/** Height of a line of text, gap included. */
	static final int LINE = 7;

	static final int GLASS = 0xFF9DA67F;
	static final int BACKLIT = 0xFFB4CB72;
	static final int INK = 0xFF1B2114;
	/** Unlit segments: the faint shadow of every digit an LCD shows. */
	private static final int GHOST = 0x221B2114;

	/** Seven-segment digits: {@link #DIGIT_WIDTH} wide, {@link #DIGIT_HEIGHT} tall, strokes 2 pixels. */
	static final int DIGIT_WIDTH = 7, DIGIT_HEIGHT = 12, DIGIT_GAP = 1, DOT_WIDTH = 3;

	/** What the LCD is drawn on: the screen, or a texture for the radio in the world. Coordinates are LCD pixels. */
	interface Canvas {
		/** Fills x0 to x1, y0 to y1 (ends excluded) with {@code argb}, blending it if it is see-through. */
		void fill(int x0, int y0, int x1, int y1, int argb);

		/** Keeps drawing inside x0 to x1, y0 to y1 until {@link #endClip()}. */
		default void clip(int x0, int y0, int x1, int y1) {}

		default void endClip() {}
	}

	/** The screen, the LCD's top left corner at ({@code left}, {@code top}). */
	static Canvas onScreen(GuiGraphics graphics, int left, int top) {
		return new Canvas() {
			@Override
			public void fill(int x0, int y0, int x1, int y1, int argb) {
				graphics.fill(left + x0, top + y0, left + x1, top + y1, argb);
			}

			@Override
			public void clip(int x0, int y0, int x1, int y1) {
				graphics.enableScissor(left + x0, top + y0, left + x1, top + y1);
			}

			@Override
			public void endClip() {
				graphics.disableScissor();
			}
		};
	}

	private final Canvas canvas;
	private int glass = GLASS;

	HandheldLcd(GuiGraphics graphics, int left, int top) {
		this(onScreen(graphics, left, top));
	}

	HandheldLcd(Canvas canvas) {
		this.canvas = canvas;
	}

	/** Clears the glass, lit by the backlight or not. */
	void clear(boolean backlit) {
		glass = backlit ? BACKLIT : GLASS;
		canvas.fill(0, 0, WIDTH, HEIGHT, glass);
	}

	void rect(int x, int y, int w, int h, int color) {
		if(w > 0 && h > 0)
			canvas.fill(x, y, x + w, y + h, color);
	}

	void pixel(int x, int y) {
		rect(x, y, 1, 1, INK);
	}

	// -------------------- DOT MATRIX TEXT --------------------

	private static final Map<Character, String[]> GLYPHS = new HashMap<>();

	private static void glyph(char c, String rows) {
		GLYPHS.put(c, rows.split("/"));
	}

	static {
		glyph('0', "###/#.#/#.#/#.#/###"); glyph('1', ".#./##./.#./.#./###"); glyph('2', "###/..#/###/#../###");
		glyph('3', "###/..#/.##/..#/###"); glyph('4', "#.#/#.#/###/..#/..#"); glyph('5', "###/#../###/..#/###");
		glyph('6', "###/#../###/#.#/###"); glyph('7', "###/..#/.#./.#./.#."); glyph('8', "###/#.#/###/#.#/###");
		glyph('9', "###/#.#/###/..#/###");
		glyph('A', ".#./#.#/###/#.#/#.#"); glyph('B', "##./#.#/##./#.#/##."); glyph('C', ".##/#../#../#../.##");
		glyph('D', "##./#.#/#.#/#.#/##."); glyph('E', "###/#../##./#../###"); glyph('F', "###/#../##./#../#..");
		glyph('G', ".##/#../#.#/#.#/.##"); glyph('H', "#.#/#.#/###/#.#/#.#"); glyph('I', "###/.#./.#./.#./###");
		glyph('J', "..#/..#/..#/#.#/.#."); glyph('K', "#.#/#.#/##./#.#/#.#"); glyph('L', "#../#../#../#../###");
		glyph('M', "#...#/##.##/#.#.#/#...#/#...#"); glyph('N', "#..#/##.#/#.##/#..#/#..#");
		glyph('O', ".#./#.#/#.#/#.#/.#."); glyph('P', "##./#.#/##./#../#.."); glyph('Q', ".#./#.#/#.#/##./.##");
		glyph('R', "##./#.#/##./#.#/#.#"); glyph('S', ".##/#../.#./..#/##."); glyph('T', "###/.#./.#./.#./.#.");
		glyph('U', "#.#/#.#/#.#/#.#/###"); glyph('V', "#.#/#.#/#.#/#.#/.#."); glyph('W', "#...#/#...#/#.#.#/##.##/#...#");
		glyph('X', "#.#/#.#/.#./#.#/#.#"); glyph('Y', "#.#/#.#/.#./.#./.#."); glyph('Z', "###/..#/.#./#../###");
		glyph(' ', "../../../../.."); glyph('.', "././././#"); glyph('-', ".../.../###/.../...");
		glyph('+', ".../.#./###/.#./..."); glyph('/', "..#/..#/.#./#../#.."); glyph(':', "./#/./#/.");
		glyph('*', ".../#.#/.#./#.#/..."); glyph('#', ".#.#./#####/.#.#./#####/.#.#."); glyph('?', "###/..#/.##/.../.#.");
		glyph('!', "#/#/#/./#"); glyph('<', "..#/.#./#../.#./..#"); glyph('>', "#../.#./..#/.#./#..");
		glyph('%', "#.#/..#/.#./#../#.#"); glyph('(', ".#/#./#./#./.#"); glyph(')', "#./.#/.#/.#/#.");
		glyph(',', "./././#/#"); glyph('\'', "#/#/././."); glyph('=', ".../###/.../###/...");
		glyph('_', ".../.../.../.../###"); glyph('&', ".#./#.#/.#./#.#/.##"); glyph('@', "###/#.#/#.#/#../###");
		glyph('^', ".#./#.#/.../.../..."); glyph('~', ".../.../#.#/#.#/.#."); // ^ and ~: the arrow keys, in hints.
	}

	private static String[] glyphOf(char c) {
		String[] rows = GLYPHS.get(Character.toUpperCase(c));
		return rows != null ? rows : GLYPHS.get('?');
	}

	/** How wide {@code text} is in the dot-matrix font, the gap after the last letter left off. */
	static int textWidth(String text) {
		int width = 0;
		for(char c : text.toCharArray())
			width += glyphOf(c)[0].length() + 1;
		return Math.max(0, width - 1);
	}

	/** Draws {@code text} with its top left at ({@code x}, {@code y}), {@code scale} pixels to a dot; returns where it ends. */
	int text(String text, int x, int y, int color, int scale) {
		for(char c : text.toCharArray()) {
			String[] rows = glyphOf(c);
			for(int row = 0; row < rows.length; row++)
				for(int col = 0; col < rows[row].length(); col++)
					if(rows[row].charAt(col) == '#')
						rect(x + col * scale, y + row * scale, scale, scale, color);
			x += (rows[0].length() + 1) * scale;
		}
		return x;
	}

	int text(String text, int x, int y) {
		return text(text, x, y, INK, 1);
	}

	/** {@code text} cut to fit {@code width} pixels. */
	static String fit(String text, int width) {
		String out = text.toUpperCase(Locale.ROOT);
		while(!out.isEmpty() && textWidth(out) > width)
			out = out.substring(0, out.length() - 1);
		return out;
	}

	void textRight(String text, int right, int y) {
		text(text, right - textWidth(text), y);
	}

	/** A heading: an inverted bar across the top with {@code title} on the left and {@code note} on the right. */
	void header(String title, String note) {
		rect(0, 0, WIDTH, LINE, INK);
		note = fit(note, WIDTH - 14);
		int noteWidth = note.isEmpty() ? 0 : textWidth(note) + 2;
		text(fit(title, WIDTH - 2 - noteWidth), 1, 1, glass, 1);
		if(!note.isEmpty())
			text(note, WIDTH - 1 - textWidth(note), 1, glass, 1);
	}

	/** Line {@code index} (0 to 2) under a heading: {@code text} with {@code value} on the right; inverted if {@code selected}. */
	void row(int index, String text, String value, boolean selected) {
		int y = LINE + 1 + index * LINE;
		int color = selected ? glass : INK;
		if(selected)
			rect(0, y - 1, WIDTH, LINE, INK);
		int valueWidth = value.isEmpty() ? 0 : textWidth(value) + 3;
		text(fit(text, WIDTH - 2 - valueWidth), 1, y, color, 1);
		if(!value.isEmpty())
			text(value, WIDTH - 1 - textWidth(value), y, color, 1);
	}

	/** Lines of a list under a heading, three at a time, scrolled to keep {@code selected} in view. */
	void list(java.util.List<String[]> rows, int selected) {
		int first = Math.max(0, Math.min(selected - 1, rows.size() - 3));
		for(int i = 0; i < 3 && first + i < rows.size(); i++) {
			String[] row = rows.get(first + i);
			row(i, row[0], row.length > 1 ? row[1] : "", first + i == selected);
		}
		if(first > 0)
			arrowUp(WIDTH - 3, LINE + 1);
		if(first + 3 < rows.size())
			arrowDown(WIDTH - 3, HEIGHT - 2);
	}

	private void arrowUp(int x, int y) {
		pixel(x, y);
		rect(x - 1, y + 1, 3, 1, INK);
	}

	private void arrowDown(int x, int y) {
		rect(x - 1, y - 1, 3, 1, INK);
		pixel(x, y);
	}

	/**
	 * {@code text} running across the screen from right to left and round again, twice the size, between {@code y} and
	 * {@code y + 10}; {@code millis} since it started. Text that fits stands still.
	 */
	void marquee(String text, int y, long millis) {
		String shown = text.toUpperCase(Locale.ROOT);
		int width = textWidth(shown) * 2;
		if(width <= WIDTH - 2) {
			text(shown, (WIDTH - width) / 2, y, INK, 2);
			return;
		}
		int gap = 20;
		int offset = (int)((Math.max(0L, millis - 800L) / 45L) % (width + gap));
		canvas.clip(0, y, WIDTH, y + 10);
		text(shown, 2 - offset, y, INK, 2);
		text(shown, 2 - offset + width + gap, y, INK, 2);
		canvas.endClip();
	}

	// -------------------- SEVEN SEGMENTS --------------------

	// Segments a (top), b (top right), c (bottom right), d (bottom), e (bottom left), f (top left), g (middle).
	private static final Map<Character, Integer> SEGMENTS = new HashMap<>();

	private static void segments(char c, String lit) {
		int mask = 0;
		for(char s : lit.toCharArray())
			mask |= 1 << (s - 'a');
		SEGMENTS.put(c, mask);
	}

	static {
		segments('0', "abcdef"); segments('1', "bc"); segments('2', "abdeg"); segments('3', "abcdg"); segments('4', "bcfg");
		segments('5', "acdfg"); segments('6', "acdefg"); segments('7', "abc"); segments('8', "abcdefg"); segments('9', "abcdfg");
		segments('-', "g"); segments(' ', ""); segments('A', "abcefg"); segments('B', "cdefg"); segments('C', "adef");
		segments('D', "bcdeg"); segments('E', "adefg"); segments('F', "aefg"); segments('H', "bcefg"); segments('L', "def");
		segments('O', "abcdef"); segments('P', "abefg"); segments('R', "eg"); segments('S', "acdfg"); segments('T', "defg");
		segments('U', "bcdef"); segments('N', "ceg"); segments('_', "d");
	}

	/** How wide {@code text} is in seven-segment digits. */
	static int digitsWidth(String text) {
		int width = 0;
		for(char c : text.toCharArray())
			width += c == '.' ? DOT_WIDTH : DIGIT_WIDTH + DIGIT_GAP;
		return width;
	}

	/** {@code text} in seven-segment digits from ({@code x}, {@code y}); every digit place shows its unlit segments faintly. */
	void digits(String text, int x, int y) {
		for(char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
			if(c == '.') {
				rect(x, y + DIGIT_HEIGHT - 2, 2, 2, INK);
				x += DOT_WIDTH;
				continue;
			}
			segmentDigit(SEGMENTS.getOrDefault('8', 0), x, y, GHOST);
			segmentDigit(SEGMENTS.getOrDefault(c, 0), x, y, INK);
			x += DIGIT_WIDTH + DIGIT_GAP;
		}
	}

	/** {@code text} in seven-segment digits centred across the screen. */
	void digitsCentered(String text, int y) {
		digits(text, (WIDTH - digitsWidth(text)) / 2, y);
	}

	private void segmentDigit(int mask, int x, int y, int color) {
		int w = DIGIT_WIDTH, half = DIGIT_HEIGHT / 2;
		if((mask & 1) != 0) rect(x + 1, y, w - 2, 2, color);                     // a
		if((mask & 2) != 0) rect(x + w - 2, y + 1, 2, half - 1, color);          // b
		if((mask & 4) != 0) rect(x + w - 2, y + half, 2, half - 1, color);       // c
		if((mask & 8) != 0) rect(x + 1, y + DIGIT_HEIGHT - 2, w - 2, 2, color);  // d
		if((mask & 16) != 0) rect(x, y + half, 2, half - 1, color);              // e
		if((mask & 32) != 0) rect(x, y + 1, 2, half - 1, color);                 // f
		if((mask & 64) != 0) rect(x + 1, y + half - 1, w - 2, 2, color);         // g
	}

	// -------------------- THE MAIN DISPLAY --------------------

	/**
	 * The handheld's main display for {@code stack}. Along the top: VFO or the memory channel, a small second line (on a
	 * memory channel, its name or frequency, whichever is not shown big), B while beaconing, an envelope for a message
	 * waiting, the battery. In the middle, big: the frequency, or on a memory channel what its display setting picks
	 * (its name, frequency or number). Along the bottom: {@code status} boxed (TX, RX, CHG; empty for none), the
	 * offset and tone marks, and APRS when the module is in.
	 */
	void main(net.minecraft.world.item.ItemStack stack, String status) {
		com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability cap = stack.getCapability(com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities.VHF_HANDHELDS);
		if(cap == null)
			return;
		com.arrl.radiocraft.common.datacomponents.HandheldMemory memory = stack.getOrDefault(com.arrl.radiocraft.common.init.RadiocraftDataComponent.HANDHELD_MEMORY.value(),
				com.arrl.radiocraft.common.datacomponents.HandheldMemory.EMPTY);
		com.arrl.radiocraft.common.datacomponents.HandheldMemory.Channel channel = memory.memoryMode() ? memory.get(memory.channel()) : null;
		com.arrl.radiocraft.common.datacomponents.HandheldAprs aprs = stack.get(com.arrl.radiocraft.common.init.RadiocraftDataComponent.HANDHELD_APRS.value());
		String frequency = String.format("%.3f", cap.getFrequencyHertz() / 1_000_000.0f);
		com.arrl.radiocraft.common.datacomponents.HandheldMemory.Display display = channel == null ? com.arrl.radiocraft.common.datacomponents.HandheldMemory.Display.FREQUENCY : memory.display();
		if(display == com.arrl.radiocraft.common.datacomponents.HandheldMemory.Display.NAME && channel.name().isEmpty())
			display = com.arrl.radiocraft.common.datacomponents.HandheldMemory.Display.FREQUENCY; // Nothing to show by name.

		// Top line.
		int x = channel != null ? badge("M" + memory.channel(), 0, 1) : text("VFO", 0, 1) + 2;
		String small = channel == null ? "" : display == com.arrl.radiocraft.common.datacomponents.HandheldMemory.Display.FREQUENCY ? channel.name() : frequency;
		int right = WIDTH - 8;
		// REC while the server logs radio voice (moderation), left of the APRS marks; the second line gives way to it.
		int recX = right - (aprs != null ? 11 : 2) - textWidth("REC");
		boolean rec = com.arrl.radiocraft.client.screens.RecIndicator.recording();
		text(fit(small, rec ? Math.min(30, recX - x - 3) : 30), x + 1, 1);
		if(rec)
			text("REC", recX, 1);
		net.neoforged.neoforge.energy.IEnergyStorage energy = stack.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
		if(energy != null && energy.getMaxEnergyStored() > 0)
			battery(right, 1, (float)energy.getEnergyStored() / energy.getMaxEnergyStored());
		if(aprs != null) {
			if(aprs.hasUnread()) {
				if((System.currentTimeMillis() / 500L) % 2L == 0L)
					envelope(right - 9, 1);
			}
			else if(aprs.beacon())
				textRight("B", right - 2, 1); // Beaconing.
		}

		// Big.
		switch(display) {
			case NAME -> {
				String name = channel.name();
				if(textWidth(name) * 2 <= WIDTH - 2)
					text(name, (WIDTH - textWidth(name) * 2) / 2, 9, INK, 2);
				else // Wide letters (M, W) do not fit twice the size: all of the name, small.
					text(name, (WIDTH - textWidth(name)) / 2, 11);
			}
			case CHANNEL -> digitsCentered("CH " + memory.channel(), 8);
			case FREQUENCY -> digitsCentered(frequency, 8);
		}

		// Bottom line.
		int after = status.isEmpty() ? 0 : badge(status, 0, 22) + 1;
		text(marks(cap.getOffsetHz(), cap.getCtcssTone(), cap.getRxTone()), after, 22);
		if(aprs != null)
			textRight("APRS", WIDTH - 1, 22);
	}

	/** The offset sign and tone letters (T: TX tone, R: RX tone), as the main display shows them. */
	static String marks(int offsetHz, float txTone, float rxTone) {
		return (offsetHz == 0 ? "" : offsetHz < 0 ? "-" : "+") + (txTone > 0 ? "T" : "") + (rxTone > 0 ? "R" : "");
	}

	// -------------------- ICONS --------------------

	/** A battery 7 by 5 at ({@code x}, {@code y}), filled from the left to {@code charge} (0 to 1). */
	void battery(int x, int y, float charge) {
		rect(x, y, 6, 1, INK);
		rect(x, y + 4, 6, 1, INK);
		rect(x, y, 1, 5, INK);
		rect(x + 5, y, 1, 5, INK);
		rect(x + 6, y + 1, 1, 3, INK);
		rect(x + 1, y + 1, Math.round(Math.max(0.0F, Math.min(1.0F, charge)) * 4.0F), 3, INK);
	}

	/** An envelope 7 by 5, for a message waiting. */
	void envelope(int x, int y) {
		rect(x, y, 7, 1, INK);
		rect(x, y + 4, 7, 1, INK);
		rect(x, y, 1, 5, INK);
		rect(x + 6, y, 1, 5, INK);
		pixel(x + 1, y + 1);
		pixel(x + 5, y + 1);
		pixel(x + 2, y + 2);
		pixel(x + 4, y + 2);
		pixel(x + 3, y + 3);
	}

	/** {@code label} boxed in ink with the glass showing through the letters, as an LCD's annunciators. */
	int badge(String label, int x, int y) {
		int w = textWidth(label) + 2;
		rect(x, y - 1, w, 7, INK);
		text(label, x + 1, y, glass, 1);
		return x + w + 1;
	}

	/**
	 * A compass of radius {@code r} at ({@code cx}, {@code cy}): its ring, an N tick and a needle at {@code angle}
	 * radians clockwise from straight up; just the centre dot if the angle is NaN (standing on it).
	 */
	void compass(int cx, int cy, int r, double angle) {
		for(int i = 0; i < 40; i++) {
			double a = i * Math.PI * 2.0D / 40.0D;
			pixel(cx + (int)Math.round(Math.sin(a) * r), cy - (int)Math.round(Math.cos(a) * r));
		}
		rect(cx - 1, cy - 1, 3, 3, INK);
		if(Double.isNaN(angle))
			return;
		for(int step = 2; step <= r - 1; step++)
			pixel(cx + (int)Math.round(Math.sin(angle) * step), cy - (int)Math.round(Math.cos(angle) * step));
		int tipX = cx + (int)Math.round(Math.sin(angle) * (r - 2));
		int tipY = cy - (int)Math.round(Math.cos(angle) * (r - 2));
		rect(tipX - 1, tipY - 1, 3, 3, INK);
	}
}
