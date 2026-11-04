package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Supplier;

/** A checkbox: a small sunken white box with a black tick, its label to the right; the label toggles it too. */
class W98Checkbox extends W98Component {

	private final Supplier<String> label;
	private boolean checked = false;
	private Runnable onChange = () -> {};

	W98Checkbox(Supplier<String> label) {
		this.label = label;
	}

	W98Checkbox onChange(Runnable action) {
		onChange = action;
		return this;
	}

	boolean checked() {
		return checked;
	}

	void setChecked(boolean value) {
		checked = value;
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if(!visible)
			return;
		int box = 11, by = y + (h - box) / 2;
		Win98.sunken(g, x, by, box, box, Win98.FIELD);
		if(checked) { // A tick, three pixels thick as Windows draws it.
			for(int i = 0; i < 3; i++) {
				g.fill(x + 3 + i, by + 5 + i, x + 4 + i, by + 8 + i, Win98.TEXT);
				g.fill(x + 6 + i, by + 6 - i, x + 7 + i, by + 9 - i, Win98.TEXT);
			}
		}
		if(w - box - 4 >= font.width("...")) // Squeezed to just its box, no label spilling out.
			Win98.text(g, font, Win98.fit(font, label.get(), w - box - 4), x + box + 4, y + (h - 8) / 2, Win98.TEXT);
	}

	@Override
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if(button != 0 || !contains(mouseX, mouseY))
			return false;
		checked = !checked;
		onChange.run();
		return true;
	}
}
