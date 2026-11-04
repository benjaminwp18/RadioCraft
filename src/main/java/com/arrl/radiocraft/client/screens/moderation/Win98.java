package com.arrl.radiocraft.client.screens.moderation;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * Drawing in the Windows 98 style, all with plain fills and the vanilla font: grey faces, 2 pixel bevels (white and
 * light grey top left, black and dark grey bottom right for a raised part, the other way round for a sunken one), a
 * navy title bar, white sunken fields and navy selections.
 */
final class Win98 {

	private Win98() {}

	static final int FACE = 0xFFC0C0C0;
	static final int HIGHLIGHT = 0xFFFFFFFF;
	static final int LIGHT = 0xFFDFDFDF;
	static final int SHADOW = 0xFF808080;
	static final int DARK = 0xFF000000;
	static final int FIELD = 0xFFFFFFFF;
	static final int TEXT = 0xFF000000;
	static final int GREY_TEXT = 0xFF808080;
	static final int NAVY = 0xFF000080;
	static final int TITLE_FADE = 0xFF1084D0;
	static final int SELECTION = 0xFF000080;
	static final int SELECTED_TEXT = 0xFFFFFFFF;
	static final int TOOLTIP = 0xFFFFFFE1;
	static final int RED = 0xFFC00000;
	static final int GREEN = 0xFF008000;

	/** A raised face (window, button, tab) with its bevel. */
	static void raised(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, FACE);
		bevel(g, x, y, w, h, HIGHLIGHT, LIGHT, DARK, SHADOW);
	}

	/** A button pressed in: the bevel turned round. */
	static void pressed(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, FACE);
		bevel(g, x, y, w, h, DARK, SHADOW, HIGHLIGHT, LIGHT);
	}

	/** A sunken field filled with {@code fill} (white for text boxes and lists). */
	static void sunken(GuiGraphics g, int x, int y, int w, int h, int fill) {
		g.fill(x, y, x + w, y + h, fill);
		bevel(g, x, y, w, h, SHADOW, DARK, HIGHLIGHT, LIGHT);
	}

	/** A thin sunken frame (status bar panes, group lines): one pixel each way. */
	static void thinSunken(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + 1, SHADOW);
		g.fill(x, y, x + 1, y + h, SHADOW);
		g.fill(x, y + h - 1, x + w, y + h, HIGHLIGHT);
		g.fill(x + w - 1, y, x + w, y + h, HIGHLIGHT);
	}

	/** Two pixels of bevel: outer and inner colours for the top left, then for the bottom right. */
	private static void bevel(GuiGraphics g, int x, int y, int w, int h, int outerTopLeft, int innerTopLeft, int outerBottomRight, int innerBottomRight) {
		g.fill(x, y + h - 1, x + w, y + h, outerBottomRight);
		g.fill(x + w - 1, y, x + w, y + h, outerBottomRight);
		g.fill(x, y, x + w - 1, y + 1, outerTopLeft);
		g.fill(x, y, x + 1, y + h - 1, outerTopLeft);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, innerBottomRight);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, innerBottomRight);
		g.fill(x + 1, y + 1, x + w - 2, y + 2, innerTopLeft);
		g.fill(x + 1, y + 1, x + 2, y + h - 2, innerTopLeft);
	}

	/** The title bar: navy fading to light blue, the title in white bold. */
	static void titleBar(GuiGraphics g, Font font, int x, int y, int w, int h, String title) {
		int strips = 16; // A left to right fade, drawn in strips (fillGradient only runs top to bottom).
		for(int i = 0; i < strips; i++) {
			int from = x + w * i / strips, to = x + w * (i + 1) / strips;
			g.fill(from, y, to, y + h, blend(NAVY, TITLE_FADE, (float)i / (strips - 1)));
		}
		text(g, font, "§l" + title, x + 3, y + (h - 8) / 2, HIGHLIGHT);
	}

	/** {@code a} to {@code b} by {@code t} (0 to 1), opaque. */
	static int blend(int a, int b, float t) {
		int r = (int)(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int gr = (int)(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int bl = (int)((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return 0xFF000000 | r << 16 | gr << 8 | bl;
	}

	/** Text with no shadow, as Windows draws it. */
	static void text(GuiGraphics g, Font font, String text, int x, int y, int colour) {
		g.drawString(font, text, x, y, colour, false);
	}

	/** Greyed out text: grey with a white copy under it, as a disabled control's. */
	static void disabledText(GuiGraphics g, Font font, String text, int x, int y) {
		g.drawString(font, text, x + 1, y + 1, HIGHLIGHT, false);
		g.drawString(font, text, x, y, GREY_TEXT, false);
	}

	/** {@code text} cut to {@code width} pixels with an ellipsis if it does not fit. */
	static String fit(Font font, String text, int width) {
		if(font.width(text) <= width)
			return text;
		String dots = "...";
		int room = Math.max(0, width - font.width(dots));
		return font.plainSubstrByWidth(text, room) + dots;
	}

	/** A push button: raised, or pressed in while held down or latched; the label centred, greyed when disabled. */
	static void button(GuiGraphics g, Font font, int x, int y, int w, int h, String label, boolean down, boolean enabled) {
		if(down)
			pressed(g, x, y, w, h);
		else
			raised(g, x, y, w, h);
		String shown = fit(font, label, w - 6);
		int tx = x + (w - font.width(shown)) / 2 + (down ? 1 : 0), ty = y + (h - 8) / 2 + (down ? 1 : 0);
		if(enabled)
			text(g, font, shown, tx, ty, TEXT);
		else
			disabledText(g, font, shown, tx, ty);
	}

	/** A small triangle pointing down (dropdown, scroll button), {@code size} wide, its top at {@code y}. */
	static void arrowDown(GuiGraphics g, int cx, int y, int size, int colour) {
		for(int row = 0; row < (size + 1) / 2; row++)
			g.fill(cx - size / 2 + row, y + row, cx + size / 2 - row + 1, y + row + 1, colour);
	}

	static void arrowUp(GuiGraphics g, int cx, int y, int size, int colour) {
		int rows = (size + 1) / 2;
		for(int row = 0; row < rows; row++)
			g.fill(cx - row, y + row, cx + row + 1, y + row + 1, colour);
	}

	/**
	 * A vertical scrollbar: up and down buttons, a dotted track and a raised thumb. Returns {@code {thumbY, thumbH}} in
	 * screen pixels for dragging.
	 */
	static int[] scrollbar(GuiGraphics g, int x, int y, int w, int h, int first, int visible, int count) {
		g.fill(x, y, x + w, y + h, 0xFFE8E8E8); // The track: Windows dithers white over grey, this is its average.
		raised(g, x, y, w, w);
		arrowUp(g, x + w / 2, y + w / 2 - 2, 7, count > visible ? TEXT : GREY_TEXT);
		raised(g, x, y + h - w, w, w);
		arrowDown(g, x + w / 2, y + h - w + w / 2 - 2, 7, count > visible ? TEXT : GREY_TEXT);
		int track = h - 2 * w;
		if(count <= visible || track <= 4)
			return new int[] { y + w, 0 };
		int[] thumb = ModerationLayout.thumb(track, first, visible, count, 8);
		raised(g, x, y + w + thumb[0], w, thumb[1]);
		return new int[] { y + w + thumb[0], thumb[1] };
	}

	/** A progress bar: a sunken field filling with navy blocks to {@code fraction}. */
	static void progress(GuiGraphics g, int x, int y, int w, int h, double fraction) {
		sunken(g, x, y, w, h, FIELD);
		int inner = w - 4;
		int filled = (int)(inner * Math.max(0.0D, Math.min(1.0D, fraction)));
		int block = Math.max(4, h - 2);
		for(int bx = 0; bx < filled; bx += block + 1)
			g.fill(x + 2 + bx, y + 2, x + 2 + Math.min(filled, bx + block), y + h - 2, SELECTION);
	}

	/** A pale yellow tooltip box with a black border round {@code lines}, kept inside {@code maxX} x {@code maxY}. */
	static void tooltip(GuiGraphics g, Font font, java.util.List<String> lines, int x, int y, int maxX, int maxY) {
		if(lines.isEmpty())
			return;
		int w = 0;
		for(String line : lines)
			w = Math.max(w, font.width(line));
		w += 6;
		int h = lines.size() * 10 + 4;
		int tx = Math.min(x + 10, maxX - w), ty = Math.min(y + 10, maxY - h);
		tx = Math.max(0, tx);
		ty = Math.max(0, ty);
		g.pose().pushPose();
		g.pose().translate(0.0F, 0.0F, 300.0F); // Over everything else on the screen.
		g.fill(tx, ty, tx + w, ty + h, DARK);
		g.fill(tx + 1, ty + 1, tx + w - 1, ty + h - 1, TOOLTIP);
		for(int i = 0; i < lines.size(); i++)
			text(g, font, lines.get(i), tx + 3, ty + 3 + i * 10, TEXT);
		g.pose().popPose();
	}

	/**
	 * A straight line {@code thickness} pixels wide from ({@code x1}, {@code y1}) to ({@code x2}, {@code y2}), as one quad
	 * (a link on the graph). Wound as {@link GuiGraphics#fill} winds its quads, so it is not culled.
	 */
	static void line(GuiGraphics g, double x1, double y1, double x2, double y2, float thickness, int colour) {
		double dx = x2 - x1, dy = y2 - y1;
		double length = Math.sqrt(dx * dx + dy * dy);
		if(length < 0.01D)
			return;
		double nx = -dy / length * thickness / 2.0D, ny = dx / length * thickness / 2.0D;
		double[][] corners = { { x1 + nx, y1 + ny }, { x1 - nx, y1 - ny }, { x2 - nx, y2 - ny }, { x2 + nx, y2 + ny } };
		double area = 0.0D;
		for(int i = 0; i < 4; i++) {
			double[] a = corners[i], b = corners[(i + 1) % 4];
			area += a[0] * b[1] - b[0] * a[1];
		}
		if(area > 0.0D) { // fill's quads have a negative area in screen space (y down).
			double[] swap = corners[1];
			corners[1] = corners[3];
			corners[3] = swap;
		}
		Matrix4f pose = g.pose().last().pose();
		VertexConsumer consumer = g.bufferSource().getBuffer(RenderType.gui());
		for(double[] corner : corners)
			consumer.addVertex(pose, (float)corner[0], (float)corner[1], 0.0F).setColor(colour);
		g.flush();
	}
}
