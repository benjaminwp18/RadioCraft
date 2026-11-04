package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.util.StringUtil;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.function.Supplier;

/**
 * A one line text box: a sunken white field with black text and a blinking caret, scrolled to keep the caret in view.
 * Typing, backspace, delete, arrows, home and end, ctrl+A and ctrl+V; Enter runs {@link #onEnter}. A grey hint shows
 * while it is empty. Each key typed calls {@link #onKey} (the screen's keyboard click).
 */
class W98TextBox extends W98Component {

	private String value = "";
	private int caret = 0;
	private int scroll = 0;
	private int maxLength = 64;
	private Supplier<String> hint = () -> "";
	private Runnable onEnter = () -> {};
	private Runnable onKey = () -> {};

	W98TextBox maxLength(int max) {
		maxLength = max;
		return this;
	}

	W98TextBox hint(Supplier<String> hint) {
		this.hint = hint;
		return this;
	}

	W98TextBox onEnter(Runnable action) {
		onEnter = action;
		return this;
	}

	W98TextBox onKey(Runnable action) {
		onKey = action;
		return this;
	}

	String value() {
		return value;
	}

	void setValue(String text) {
		value = text.length() > maxLength ? text.substring(0, maxLength) : text;
		caret = value.length();
		scroll = 0;
	}

	@Override
	boolean takesFocus() {
		return true;
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if(!visible)
			return;
		Win98.sunken(g, x, y, w, h, Win98.FIELD);
		int inner = w - 6;
		int ty = y + (h - 8) / 2;
		if(value.isEmpty() && !focused) {
			Win98.text(g, font, Win98.fit(font, hint.get(), inner), x + 3, ty, Win98.GREY_TEXT);
			return;
		}
		keepCaretVisible(font, inner);
		String shown = font.plainSubstrByWidth(value.substring(scroll), inner);
		Win98.text(g, font, shown, x + 3, ty, Win98.TEXT);
		if(focused && (Util.getMillis() / 500L) % 2L == 0L) {
			int cx = x + 3 + font.width(value.substring(scroll, Math.max(scroll, caret)));
			if(cx < x + w - 2)
				g.fill(cx, ty - 1, cx + 1, ty + 9, Win98.TEXT);
		}
	}

	private void keepCaretVisible(Font font, int inner) {
		scroll = Math.min(scroll, caret);
		while(scroll < caret && font.width(value.substring(scroll, caret)) > inner - 2)
			scroll++;
	}

	@Override
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if(!contains(mouseX, mouseY))
			return false;
		Font font = Minecraft.getInstance().font;
		int offset = (int)mouseX - (x + 3);
		String visibleText = value.substring(scroll);
		caret = scroll + font.plainSubstrByWidth(visibleText, Math.max(0, offset)).length();
		return true;
	}

	@Override
	boolean keyPressed(int key, int scanCode, int modifiers) {
		if(!focused)
			return false;
		if(Screen.isPaste(key)) {
			insert(Minecraft.getInstance().keyboardHandler.getClipboard());
			return true;
		}
		if(Screen.isSelectAll(key)) {
			caret = value.length();
			return true;
		}
		switch(key) {
			case GLFW.GLFW_KEY_BACKSPACE -> {
				if(caret > 0) {
					value = value.substring(0, caret - 1) + value.substring(caret);
					caret--;
					onKey.run();
				}
			}
			case GLFW.GLFW_KEY_DELETE -> {
				if(caret < value.length()) {
					value = value.substring(0, caret) + value.substring(caret + 1);
					onKey.run();
				}
			}
			case GLFW.GLFW_KEY_LEFT -> caret = Math.max(0, caret - 1);
			case GLFW.GLFW_KEY_RIGHT -> caret = Math.min(value.length(), caret + 1);
			case GLFW.GLFW_KEY_HOME -> caret = 0;
			case GLFW.GLFW_KEY_END -> caret = value.length();
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
				onKey.run();
				onEnter.run();
			}
			default -> {
				return key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_TAB; // Everything else stays in the box (the inventory key must not close the screen).
			}
		}
		return true;
	}

	@Override
	boolean charTyped(char c, int modifiers) {
		if(!focused || !StringUtil.isAllowedChatCharacter(c))
			return false;
		insert(Character.toString(c));
		return true;
	}

	private void insert(String text) {
		StringBuilder clean = new StringBuilder();
		for(char c : text.toCharArray())
			if(StringUtil.isAllowedChatCharacter(c))
				clean.append(c);
		int room = maxLength - value.length();
		if(room <= 0 || clean.isEmpty())
			return;
		String add = clean.length() > room ? clean.substring(0, room) : clean.toString();
		value = value.substring(0, caret) + add + value.substring(caret);
		caret += add.length();
		onKey.run();
	}
}
