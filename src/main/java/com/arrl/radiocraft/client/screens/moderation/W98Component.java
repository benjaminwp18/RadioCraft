package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A control on the moderation computer's screen, drawn in the Windows 98 style ({@link Win98}). The screen lays them
 * out and hands them the mouse and keys, in its own (possibly scaled down) coordinates.
 */
abstract class W98Component {

	int x, y, w, h;
	boolean visible = true;
	boolean focused = false;

	W98Component bounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		return this;
	}

	boolean contains(double mouseX, double mouseY) {
		return visible && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	abstract void render(GuiGraphics g, Font font, int mouseX, int mouseY);

	/** Drawn after everything else (an open dropdown's list, a tooltip). */
	void renderOverlay(GuiGraphics g, Font font, int mouseX, int mouseY) {}

	/** True if a click at the mouse lands on this control's overlay (an open dropdown list), which is over the others. */
	boolean overlayContains(double mouseX, double mouseY) {
		return false;
	}

	boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}

	boolean mouseDragged(double mouseX, double mouseY, int button) {
		return false;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	boolean keyPressed(int key, int scanCode, int modifiers) {
		return false;
	}

	boolean charTyped(char c, int modifiers) {
		return false;
	}

	/** True for controls that take the keyboard when clicked (text boxes). */
	boolean takesFocus() {
		return false;
	}

	/** Closes anything it has open (a dropdown's list) when another control is clicked. */
	void closePopup() {}
}
