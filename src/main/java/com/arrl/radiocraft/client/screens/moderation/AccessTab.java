package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.common.moderation.ModerationFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

import java.time.ZoneId;

/**
 * Access: who may open this computer, and the audit log. The owner adds admins by player name (online, or known to the
 * server), removes them, or hands the computer over to someone else (staying on as an admin); admins see the list but
 * cannot change it. On the right, read only, every open, listen, playback, export, flag and change of admins logged
 * with who and when. Reboot starts the computer afresh: a full boot, live listening and playback stopped, every tab
 * as new.
 */
class AccessTab extends ComputerTab {

	private final W98ListView<CompoundTag> admins, audit;
	private final W98TextBox addName, transferName;
	private final W98Button add, remove, transfer, refresh, reboot;
	private final W98Dropdown range;
	private int splitX;

	AccessTab(ModerationDesk screen) {
		super(screen);
		admins = add(new W98ListView<CompoundTag>(row -> new String[] { row.getString("name") }, new int[] { 1 }, ModerationComputerScreen.columns("col.admins")))
				.empty(() -> ModerationComputerScreen.tr("access.no_admins"))
				.sortedBy(row -> new Object[] { row.getString("name") });
		addName = add(new W98TextBox().maxLength(16).hint(() -> ModerationComputerScreen.tr("access.name_hint")).onKey(screen::keyClick).onEnter(this::addAdmin));
		add = add(new W98Button(() -> ModerationComputerScreen.tr("access.add"), this::addAdmin).enabledWhen(this::isOwner));
		remove = add(new W98Button(() -> ModerationComputerScreen.tr("access.remove"), this::removeAdmin).enabledWhen(() -> isOwner() && admins.selectedItem() != null));
		transferName = add(new W98TextBox().maxLength(16).hint(() -> ModerationComputerScreen.tr("access.name_hint")).onKey(screen::keyClick));
		transfer = add(new W98Button(() -> ModerationComputerScreen.tr("access.transfer"), this::transfer).enabledWhen(this::isOwner));
		range = add(new W98Dropdown(ComputerTab::rangeLabels).onChange(i -> askAudit()));
		range.select(ModerationFormat.Range.LAST_7_DAYS.ordinal());
		refresh = add(new W98Button(() -> ModerationComputerScreen.tr("map.refresh"), this::askAudit));
		reboot = add(new W98Button(() -> ModerationComputerScreen.tr("access.reboot"), screen::reboot));
		audit = add(new W98ListView<CompoundTag>(this::auditCells, new int[] { 18, 14, 12, 18, 30 },
				ModerationComputerScreen.columns("col.time", "col.who", "col.action", "col.transmission", "col.detail")))
				.empty(() -> ModerationComputerScreen.tr("access.no_audit"))
				.sortedBy(row -> new Object[] { row.getLong("ms"), row.getString("who"), row.getString("action"), row.getLong("target"), row.getString("detail") });
	}

	@Override
	String title() {
		return ModerationComputerScreen.tr("tab.access");
	}

	private boolean isOwner() {
		return screen.access.getBoolean("isOwner");
	}

	@Override
	void place() {
		splitX = x + Math.max(150, w * 2 / 5);
		int leftW = splitX - x - 6;
		int top = y + 2 * (ROW + GAP);
		int bottom = y + h - 4 * (ROW + GAP) - 6;
		admins.bounds(x, top, leftW, bottom - top);
		int row = bottom + GAP + 4;
		int addW = font().width(ModerationComputerScreen.tr("access.add")) + 14;
		addName.bounds(x, row, leftW - addW - GAP, ROW);
		add.bounds(x + leftW - addW, row, addW, ROW);
		row += ROW + GAP;
		remove.bounds(x, row, font().width(ModerationComputerScreen.tr("access.remove")) + 14, ROW);
		int rebootW = font().width(ModerationComputerScreen.tr("access.reboot")) + 14;
		reboot.bounds(x + leftW - rebootW, row, rebootW, ROW);
		row += 2 * (ROW + GAP); // A line for the hand over label.
		int transferW = font().width(ModerationComputerScreen.tr("access.transfer")) + 14;
		transferName.bounds(x, row, leftW - transferW - GAP, ROW);
		transfer.bounds(x + leftW - transferW, row, transferW, ROW);

		int cx = label(() -> ModerationComputerScreen.tr("access.audit"), splitX, y);
		cx = label(() -> ModerationComputerScreen.tr("archive.when"), cx + 6, y);
		range.bounds(cx, y, 84, ROW);
		refresh.bounds(cx + 88, y, font().width(ModerationComputerScreen.tr("map.refresh")) + 14, ROW);
		audit.bounds(splitX, y + ROW + GAP, x + w - splitX, h - ROW - GAP);
	}

	@Override
	void shown() {
		screen.send("access");
		askAudit();
	}

	private void askAudit() {
		CompoundTag data = new CompoundTag();
		data.putInt("range", range.selected());
		screen.send("audit", data);
	}

	private void addAdmin() {
		if(addName.value().isBlank() || !isOwner())
			return;
		CompoundTag data = new CompoundTag();
		data.putString("name", addName.value().trim());
		screen.send("add_admin", data);
		addName.setValue("");
	}

	private void removeAdmin() {
		CompoundTag row = admins.selectedItem();
		if(row == null)
			return;
		CompoundTag data = new CompoundTag();
		data.putString("uuid", row.getString("uuid"));
		screen.send("remove_admin", data);
	}

	private void transfer() {
		if(transferName.value().isBlank())
			return;
		CompoundTag data = new CompoundTag();
		data.putString("name", transferName.value().trim());
		screen.send("transfer", data);
		transferName.setValue("");
	}

	private String[] auditCells(CompoundTag row) {
		return new String[] { ModerationFormat.time(row.getLong("ms"), ZoneId.systemDefault()), row.getString("who"), row.getString("action"),
				row.getLong("target") == 0L ? "-" : "#" + row.getLong("target"), row.getString("detail") };
	}

	@Override
	void receive(String kind, CompoundTag data) {
		switch(kind) {
			case "access" -> admins.setItems(rows(data, "admins"), row -> row.getString("uuid"));
			case "audit" -> audit.setItems(rows(data, "rows"));
			default -> {}
		}
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		super.render(g, font, mouseX, mouseY);
		String owner = screen.access.getString("owner");
		Win98.text(g, font, Win98.fit(font, ModerationComputerScreen.tr("access.owner", owner.isEmpty() ? "-" : owner), splitX - x - 6), x, y + 3, Win98.TEXT);
		String note = ModerationComputerScreen.tr(isOwner() ? "access.you_own" : "access.owner_only");
		Win98.text(g, font, Win98.fit(font, note, splitX - x - 6), x, y + ROW + GAP + 3, Win98.GREY_TEXT);
		Win98.text(g, font, Win98.fit(font, ModerationComputerScreen.tr("access.transfer_label"), splitX - x - 6), x, transferName.y - ROW, Win98.TEXT);
	}
}
