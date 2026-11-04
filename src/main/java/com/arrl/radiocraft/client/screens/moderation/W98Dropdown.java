package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A dropdown list: a sunken field showing the choice and a raised arrow button; clicking opens the choices below it
 * (above, if there is no room), drawn over everything, with the one under the mouse highlighted in navy.
 */
class W98Dropdown extends W98Component {

	private static final int ROW = 11;
	private static final int MAX_ROWS = 10;

	private final Supplier<List<String>> options;
	private int selected = 0;
	private IntConsumer onChange = i -> {};
	private boolean open = false;
	private int scroll = 0;
	/** The bottom of the screen area, so the list can open upwards near it. */
	int limitY = Integer.MAX_VALUE;

	W98Dropdown(Supplier<List<String>> options) {
		this.options = options;
	}

	W98Dropdown onChange(IntConsumer action) {
		onChange = action;
		return this;
	}

	int selected() {
		List<String> all = options.get();
		return all.isEmpty() ? 0 : Math.min(selected, all.size() - 1);
	}

	void select(int index) {
		selected = Math.max(0, index);
	}

	String selectedLabel() {
		List<String> all = options.get();
		return all.isEmpty() ? "" : all.get(selected());
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if(!visible)
			return;
		Win98.sunken(g, x, y, w, h, Win98.FIELD);
		int button = h - 4;
		Win98.text(g, font, Win98.fit(font, selectedLabel(), w - button - 8), x + 3, y + (h - 8) / 2, Win98.TEXT);
		if(open)
			Win98.pressed(g, x + w - button - 2, y + 2, button, button);
		else
			Win98.raised(g, x + w - button - 2, y + 2, button, button);
		Win98.arrowDown(g, x + w - 2 - button / 2 - 1, y + h / 2 - 1, 7, Win98.TEXT);
	}

	private int rows() {
		return Math.min(MAX_ROWS, options.get().size());
	}

	/** The open list's top: below the field, or above it if it would run past {@link #limitY}. */
	private int listY() {
		int height = rows() * ROW + 2;
		return y + h + height > limitY ? y - height : y + h;
	}

	@Override
	boolean overlayContains(double mouseX, double mouseY) {
		return open && mouseX >= x && mouseX < x + w && mouseY >= listY() && mouseY < listY() + rows() * ROW + 2;
	}

	@Override
	void renderOverlay(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if(!open || !visible)
			return;
		List<String> all = options.get();
		int top = listY();
		g.pose().pushPose();
		g.pose().translate(0.0F, 0.0F, 200.0F);
		g.fill(x, top, x + w, top + rows() * ROW + 2, Win98.DARK);
		g.fill(x + 1, top + 1, x + w - 1, top + rows() * ROW + 1, Win98.FIELD);
		scroll = Math.max(0, Math.min(scroll, all.size() - rows()));
		for(int i = 0; i < rows(); i++) {
			int index = scroll + i;
			int ry = top + 1 + i * ROW;
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW;
			if(hover)
				g.fill(x + 1, ry, x + w - 1, ry + ROW, Win98.SELECTION);
			Win98.text(g, font, Win98.fit(font, all.get(index), w - 6), x + 3, ry + 2, hover ? Win98.SELECTED_TEXT : Win98.TEXT);
		}
		g.pose().popPose();
	}

	@Override
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if(overlayContains(mouseX, mouseY)) {
			int index = scroll + (int)((mouseY - listY() - 1) / ROW);
			if(index >= 0 && index < options.get().size()) {
				selected = index;
				onChange.accept(index);
			}
			open = false;
			return true;
		}
		if(contains(mouseX, mouseY)) {
			open = !open && !options.get().isEmpty();
			return true;
		}
		if(open) {
			open = false;
			return true; // A click away closes the list and does nothing else, as in Windows.
		}
		return false;
	}

	@Override
	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if(overlayContains(mouseX, mouseY)) {
			scroll -= (int)Math.signum(amount);
			return true;
		}
		return false;
	}

	@Override
	void closePopup() {
		open = false;
	}

	boolean isOpen() {
		return open;
	}
}
