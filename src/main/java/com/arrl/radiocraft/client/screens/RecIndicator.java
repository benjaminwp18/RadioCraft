package com.arrl.radiocraft.client.screens;

import com.arrl.radiocraft.RadiocraftServerConfig;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The small red "REC" radio screens show while the server logs radio voice ({@code moderation_record_radio}, a server
 * setting the client gets with the rest of the server config): part of the moderation log's privacy design, kept
 * subtle.
 */
public final class RecIndicator {

	private RecIndicator() {}

	private static final int RED = 0xFFE02020;
	private static final float SCALE = 0.5F;

	/** True while the server records radio voice; false when there is no server config to read. */
	public static boolean recording() {
		try {
			return RadiocraftServerConfig.MODERATION_RECORD_RADIO.get();
		}
		catch(IllegalStateException e) {
			return false; // Not connected, or the server config is not loaded yet.
		}
	}

	/** Draws a red dot and "REC" at half size with its top right corner at ({@code right}, {@code top}), if recording. */
	public static void draw(GuiGraphics g, Font font, int right, int top) {
		if(!recording())
			return;
		int width = (int)Math.ceil(font.width("REC") * SCALE);
		g.pose().pushPose();
		g.pose().translate(right - width, top, 0.0F);
		g.pose().scale(SCALE, SCALE, 1.0F);
		g.drawString(font, "REC", 0, 0, RED, false);
		g.pose().popPose();
		g.fill(right - width - 4, top + 1, right - width - 1, top + 4, RED);
	}
}
