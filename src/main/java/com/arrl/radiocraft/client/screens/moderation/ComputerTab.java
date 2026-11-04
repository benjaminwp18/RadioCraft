package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One page of the moderation computer's screen (Live, Archive, Map...): its controls, laid out in the content area it
 * is given, its own drawing, and the answers from the server it cares about.
 */
abstract class ComputerTab {

	/** Height of a row of controls, and the gap between them. */
	static final int ROW = 13, GAP = 3;

	protected final ModerationDesk screen;
	final List<W98Component> components = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();
	int x, y, w, h;

	private record Label(Supplier<String> text, int x, int y) {}

	ComputerTab(ModerationDesk screen) {
		this.screen = screen;
	}

	/** The tab's label. */
	abstract String title();

	/** Every control the tab has, shown or not (the Proximity tab's two tools both). */
	java.util.List<W98Component> allComponents() {
		return components;
	}

	<C extends W98Component> C add(C component) {
		components.add(component);
		return component;
	}

	/** Lays the tab out in {@code x, y, w, h}. */
	final void layout(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		labels.clear();
		place();
	}

	/** Places the controls in {@link #x}, {@link #y}, {@link #w}, {@link #h}. */
	abstract void place();

	/** A label at {@code x} on the row at {@code y}; returns where the next control goes. */
	int label(Supplier<String> text, int x, int y) {
		labels.add(new Label(text, x, y + (ROW - 8) / 2));
		return x + font().width(text.get()) + 4;
	}

	static Font font() {
		return Minecraft.getInstance().font;
	}

	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		for(Label label : labels)
			Win98.text(g, font, label.text().get(), label.x(), label.y(), Win98.TEXT);
		for(W98Component component : components)
			component.render(g, font, mouseX, mouseY);
	}

	/** Tooltips, drawn last. */
	void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {}

	/** An answer from the server (see {@code ComputerRequests}). */
	void receive(String kind, CompoundTag data) {}

	/** Every client tick while this tab shows. */
	void tick(int ticks) {}

	/** The tab was just picked. */
	void shown() {}

	/** The screen closed: let go of anything held (a map texture). */
	void closed() {}

	/** A click not taken by a control (on a map or graph). */
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	boolean mouseReleased(double mouseX, double mouseY, int button) {
		return false;
	}

	boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		return false;
	}

	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return false;
	}

	// -------------------- HELPERS FOR ANSWERS --------------------

	static List<CompoundTag> rows(CompoundTag data, String key) {
		List<CompoundTag> out = new ArrayList<>();
		for(Tag tag : data.getList(key, Tag.TAG_COMPOUND))
			out.add((CompoundTag)tag);
		return out;
	}

	static List<String> strings(CompoundTag data, String key) {
		List<String> out = new ArrayList<>();
		ListTag list = data.getList(key, Tag.TAG_STRING);
		for(int i = 0; i < list.size(); i++)
			out.add(list.getString(i));
		return out;
	}

	/** The time range presets, as the dropdowns list them. */
	static List<String> rangeLabels() {
		List<String> out = new ArrayList<>();
		for(com.arrl.radiocraft.common.moderation.ModerationFormat.Range range : com.arrl.radiocraft.common.moderation.ModerationFormat.Range.values())
			out.add(ModerationComputerScreen.tr("range." + range.id()));
		return out;
	}
}
