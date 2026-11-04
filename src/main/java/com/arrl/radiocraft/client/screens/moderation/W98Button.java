package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A push button that presses in while the mouse is held on it and acts when released over it, as Windows' do. A
 * latched button ({@link #latched}) stays pressed in, for toggles.
 */
class W98Button extends W98Component {

	private final Supplier<String> label;
	private final Runnable action;
	private BooleanSupplier enabled = () -> true;
	private BooleanSupplier latched = () -> false;
	private boolean held = false;

	W98Button(Supplier<String> label, Runnable action) {
		this.label = label;
		this.action = action;
	}

	W98Button enabledWhen(BooleanSupplier enabled) {
		this.enabled = enabled;
		return this;
	}

	W98Button latchedWhen(BooleanSupplier latched) {
		this.latched = latched;
		return this;
	}

	boolean isEnabled() {
		return enabled.getAsBoolean();
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if(!visible)
			return;
		boolean down = latched.getAsBoolean() || (held && contains(mouseX, mouseY));
		Win98.button(g, font, x, y, w, h, label.get(), down, isEnabled());
	}

	@Override
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if(button != 0 || !contains(mouseX, mouseY) || !isEnabled())
			return false;
		held = true;
		return true;
	}

	@Override
	boolean mouseReleased(double mouseX, double mouseY, int button) {
		if(!held)
			return false;
		held = false;
		if(contains(mouseX, mouseY) && isEnabled())
			action.run();
		return true;
	}
}
