package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

/**
 * Proximity: the Live and Archive tools for plain Simple Voice Chat (no radio), which the log keeps apart and records
 * only with {@code moderation_record_proximity}. Two buttons along the top switch between them.
 */
class ProximityTab extends ComputerTab {

	private final LiveTab live;
	private final ArchiveTab archive;
	private final W98Button liveButton, archiveButton;
	private ComputerTab current;

	ProximityTab(ModerationDesk screen) {
		super(screen);
		live = new LiveTab(screen, true);
		archive = new ArchiveTab(screen, true);
		liveButton = new W98Button(() -> ModerationComputerScreen.tr("proximity.live"), () -> show(live)).latchedWhen(() -> current == live);
		archiveButton = new W98Button(() -> ModerationComputerScreen.tr("proximity.archive"), () -> show(archive)).latchedWhen(() -> current == archive);
		current = live;
		rebuild();
	}

	@Override
	String title() {
		return ModerationComputerScreen.tr("tab.proximity");
	}

	/** The controls the screen hands events to: the two switches and the shown tool's. */
	private void rebuild() {
		components.clear();
		components.add(liveButton);
		components.add(archiveButton);
		components.addAll(current.components);
	}

	@Override
	java.util.List<W98Component> allComponents() {
		java.util.List<W98Component> all = new java.util.ArrayList<>(java.util.List.of(liveButton, archiveButton));
		all.addAll(live.components);
		all.addAll(archive.components);
		return all;
	}

	private void show(ComputerTab tool) {
		if(current == tool)
			return;
		for(W98Component component : current.components)
			component.closePopup();
		current = tool;
		rebuild();
		tool.shown();
	}

	@Override
	void place() {
		int liveW = font().width(ModerationComputerScreen.tr("proximity.live")) + 16, archiveW = font().width(ModerationComputerScreen.tr("proximity.archive")) + 16;
		liveButton.bounds(x, y, liveW, ROW);
		archiveButton.bounds(x + liveW, y, archiveW, ROW);
		int top = y + ROW + GAP + 2;
		live.layout(x, top, w, h - (top - y));
		archive.layout(x, top, w, h - (top - y));
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		liveButton.render(g, font, mouseX, mouseY);
		archiveButton.render(g, font, mouseX, mouseY);
		String note = ModerationComputerScreen.tr(screen.status.getBoolean("proximity") ? "proximity.recording" : "proximity.not_recording");
		int nx = archiveButton.x + archiveButton.w + 8;
		Win98.text(g, font, Win98.fit(font, note, x + w - nx), nx, y + 3, screen.status.getBoolean("proximity") ? Win98.TEXT : Win98.GREY_TEXT);
		current.render(g, font, mouseX, mouseY);
	}

	@Override
	void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
		current.renderTooltip(g, font, mouseX, mouseY);
	}

	@Override
	void receive(String kind, CompoundTag data) {
		live.receive(kind, data);
		archive.receive(kind, data);
	}

	@Override
	void tick(int ticks) {
		current.tick(ticks);
	}

	@Override
	void shown() {
		current.shown();
	}

	@Override
	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		return current.mouseScrolled(mouseX, mouseY, amount);
	}
}
