package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.common.moderation.ModerationFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

import java.time.ZoneId;

/**
 * Tapes: player tapes recorded or sent over the air in the time range, by one speaker if a name is given: when it was
 * recorded, how long, where, who spoke on it, how often it was played over the air and when last. Play plays the tape
 * itself.
 */
class TapesTab extends ComputerTab {

	private final W98TextBox speaker;
	private final W98Dropdown range;
	private final W98Button search, play, stop;
	private final W98ListView<CompoundTag> list;
	private int progressX, progressY;

	TapesTab(ModerationDesk screen) {
		super(screen);
		speaker = add(new W98TextBox().maxLength(16).hint(() -> ModerationComputerScreen.tr("archive.anyone")).onKey(screen::keyClick).onEnter(this::ask));
		range = add(new W98Dropdown(ComputerTab::rangeLabels).onChange(i -> ask()));
		range.select(ModerationFormat.Range.LAST_7_DAYS.ordinal());
		search = add(new W98Button(() -> ModerationComputerScreen.tr("archive.search"), this::ask));
		list = add(new W98ListView<CompoundTag>(this::cells, new int[] { 20, 9, 22, 26, 8, 15 },
				ModerationComputerScreen.columns("col.recorded", "col.length", "col.position", "col.speakers", "col.plays", "col.last_played")))
				.empty(() -> ModerationComputerScreen.tr("tapes.empty"))
				.onDoubleClick(row -> play())
				.sortedBy(row -> new Object[] { row.getLong("recorded"), row.getInt("seconds"), cells(row)[2], cells(row)[3], row.getInt("plays"), row.getLong("lastPlayed") });
		play = add(new W98Button(() -> ModerationComputerScreen.tr("archive.play"), this::play)
				.enabledWhen(() -> list.selectedItem() != null && list.selectedItem().getLong("play") != 0L));
		stop = add(new W98Button(() -> ModerationComputerScreen.tr("archive.stop"), () -> screen.send("stop_play")).enabledWhen(() -> screen.playing));
	}

	@Override
	String title() {
		return ModerationComputerScreen.tr("tab.tapes");
	}

	@Override
	void place() {
		int cx = label(() -> ModerationComputerScreen.tr("tapes.speaker"), x, y);
		speaker.bounds(cx, y, 90, ROW);
		cx = label(() -> ModerationComputerScreen.tr("archive.when"), cx + 96, y);
		range.bounds(cx, y, 84, ROW);
		search.bounds(cx + 90, y, font().width(ModerationComputerScreen.tr("archive.search")) + 14, ROW);
		int bottom = y + h - ROW;
		list.bounds(x, y + ROW + GAP, w, bottom - GAP - (y + ROW + GAP));
		int playW = font().width(ModerationComputerScreen.tr("archive.play")) + 14, stopW = font().width(ModerationComputerScreen.tr("archive.stop")) + 14;
		play.bounds(x, bottom, playW, ROW);
		stop.bounds(x + playW + GAP, bottom, stopW, ROW);
		progressX = x + playW + stopW + 2 * GAP;
		progressY = bottom;
	}

	@Override
	void shown() {
		ask();
	}

	private void ask() {
		CompoundTag data = new CompoundTag();
		data.putString("player", speaker.value().trim());
		data.putInt("range", range.selected());
		screen.send("tapes", data);
	}

	private void play() {
		CompoundTag row = list.selectedItem();
		if(row == null || row.getLong("play") == 0L)
			return;
		CompoundTag data = new CompoundTag();
		data.putLong("id", row.getLong("play"));
		screen.send("play", data);
	}

	private String[] cells(CompoundTag row) {
		ZoneId zone = ZoneId.systemDefault();
		return new String[] { ModerationFormat.time(row.getLong("recorded"), zone), row.getInt("seconds") > 0 ? ModerationFormat.duration(row.getInt("seconds") * 1000L) : "-",
				row.contains("pos") ? LiveTab.pos(row.getLong("pos")) : "-", row.getString("speakers").isEmpty() ? "-" : row.getString("speakers"),
				Integer.toString(row.getInt("plays")), ModerationFormat.time(row.getLong("lastPlayed"), zone) };
	}

	@Override
	void receive(String kind, CompoundTag data) {
		if(kind.equals("tapes"))
			list.setItems(rows(data, "rows"), row -> row.getString("recording"));
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		super.render(g, font, mouseX, mouseY);
		CompoundTag row = list.selectedItem();
		boolean ours = row != null && screen.playingId == row.getLong("play");
		int barW = Math.min(200, x + w - progressX);
		if(barW > 20)
			Win98.progress(g, progressX, progressY, barW, ROW, ours && screen.playLength > 0L ? (double)screen.playPosition / screen.playLength : 0.0D);
	}
}
