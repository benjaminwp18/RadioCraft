package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.common.moderation.ModerationFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Links: who hears whom in the time range, from the hearers of each transmission (counted once per transmission per
 * day, the days added up). On the left a graph: players (navy) and radios nobody stood at (grey) round a circle, a
 * line from talker to hearer as thick as the link is busy; hovering a node shows only its links. On the right the same
 * links as a table that sorts by any column. With "voice chat" ticked, plain voice chat instead of radio.
 */
class LinksTab extends ComputerTab {

	/** One link as the table shows it. */
	private record Link(int from, int to, String fromLabel, String toLabel, int count) {}

	private final W98Dropdown range;
	private final W98Checkbox proximity;
	private final W98Button refresh;
	private final W98ListView<Link> table;
	private List<String> labels = List.of();
	private List<Boolean> players = List.of();
	private List<Link> links = List.of();
	private int maxCount = 1;
	private int graphX, graphY, graphW, graphH;

	LinksTab(ModerationDesk screen) {
		super(screen);
		range = add(new W98Dropdown(ComputerTab::rangeLabels).onChange(i -> ask()));
		range.select(ModerationFormat.Range.LAST_7_DAYS.ordinal());
		proximity = add(new W98Checkbox(() -> ModerationComputerScreen.tr("links.proximity")).onChange(this::ask));
		refresh = add(new W98Button(() -> ModerationComputerScreen.tr("map.refresh"), this::ask));
		table = add(new W98ListView<Link>(link -> new String[] { link.fromLabel(), link.toLabel(), Integer.toString(link.count()) }, new int[] { 40, 40, 14 },
				ModerationComputerScreen.columns("col.from", "col.to", "col.count")))
				.empty(() -> ModerationComputerScreen.tr("links.empty"))
				.sortable(column -> sort());
		table.setSort(2, true);
	}

	@Override
	String title() {
		return ModerationComputerScreen.tr("tab.links");
	}

	@Override
	void place() {
		int cx = label(() -> ModerationComputerScreen.tr("archive.when"), x, y);
		range.bounds(cx, y, 84, ROW);
		cx += 90;
		int checkW = font().width(ModerationComputerScreen.tr("links.proximity")) + 18;
		proximity.bounds(cx, y, checkW, ROW);
		cx += checkW + GAP;
		refresh.bounds(cx, y, font().width(ModerationComputerScreen.tr("map.refresh")) + 14, ROW);
		int top = y + ROW + GAP;
		int restH = h - ROW - GAP;
		graphW = Math.min(restH + 40, w * 55 / 100);
		graphX = x;
		graphY = top;
		graphH = restH;
		table.bounds(x + graphW + GAP, top, w - graphW - GAP, restH);
	}

	@Override
	void shown() {
		ask();
	}

	private void ask() {
		CompoundTag data = new CompoundTag();
		data.putInt("range", range.selected());
		data.putBoolean("prox", proximity.checked());
		screen.send("links", data);
	}

	@Override
	void receive(String kind, CompoundTag data) {
		if(!kind.equals("links"))
			return;
		List<String> names = new ArrayList<>();
		List<Boolean> isPlayer = new ArrayList<>();
		for(CompoundTag node : rows(data, "nodes")) {
			names.add(node.getString("label"));
			isPlayer.add(node.getBoolean("player"));
		}
		List<Link> found = new ArrayList<>();
		maxCount = 1;
		for(CompoundTag link : rows(data, "links")) {
			int from = link.getInt("from"), to = link.getInt("to");
			if(from >= names.size() || to >= names.size())
				continue;
			found.add(new Link(from, to, names.get(from), names.get(to), link.getInt("count")));
			maxCount = Math.max(maxCount, link.getInt("count"));
		}
		labels = names;
		players = isPlayer;
		links = found;
		table.setItems(found);
		sort();
	}

	private void sort() {
		Comparator<Link> order = switch(table.sortColumn()) {
			case 0 -> Comparator.comparing(link -> link.fromLabel().toLowerCase(Locale.ROOT));
			case 1 -> Comparator.comparing(link -> link.toLabel().toLowerCase(Locale.ROOT));
			default -> Comparator.comparingInt(Link::count);
		};
		List<Link> rows = new ArrayList<>(table.items());
		rows.sort(table.sortDescending() ? order.reversed() : order);
		table.setItems(rows);
	}

	private double[][] nodes() {
		double radius = Math.max(10.0D, Math.min(graphW, graphH) / 2.0D - 22.0D);
		return ModerationLayout.circle(labels.size(), graphX + graphW / 2.0D, graphY + graphH / 2.0D, radius);
	}

	private int nodeAt(double mouseX, double mouseY) {
		double[][] at = nodes();
		for(int i = 0; i < at.length; i++) {
			double dx = at[i][0] - mouseX, dy = at[i][1] - mouseY;
			if(dx * dx + dy * dy <= 25.0D)
				return i;
		}
		return -1;
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		super.render(g, font, mouseX, mouseY);
		Win98.sunken(g, graphX, graphY, graphW, graphH, Win98.FIELD);
		if(labels.isEmpty()) {
			Win98.text(g, font, Win98.fit(font, ModerationComputerScreen.tr("links.empty"), graphW - 10), graphX + 5, graphY + 5, Win98.GREY_TEXT);
			return;
		}
		double[][] at = nodes();
		int hover = nodeAt(mouseX, mouseY);
		Link picked = table.selectedItem();
		screen.scissor(g, graphX + 2, graphY + 2, graphX + graphW - 2, graphY + graphH - 2);
		for(Link link : links) {
			boolean lit = hover < 0 ? link.equals(picked) : link.from() == hover || link.to() == hover;
			if(hover >= 0 && !lit)
				continue;
			int colour = lit ? 0xFFC00000 : 0x90000080;
			Win98.line(g, at[link.from()][0], at[link.from()][1], at[link.to()][0], at[link.to()][1], ModerationLayout.thickness(link.count(), maxCount), colour);
			// The hearing end: a small square, so the direction shows.
			double ex = at[link.to()][0] + (at[link.from()][0] - at[link.to()][0]) * 0.12D, ey = at[link.to()][1] + (at[link.from()][1] - at[link.to()][1]) * 0.12D;
			g.fill((int)ex - 1, (int)ey - 1, (int)ex + 2, (int)ey + 2, colour | 0xFF000000);
		}
		for(int i = 0; i < at.length; i++) {
			int nx = (int)Math.round(at[i][0]), ny = (int)Math.round(at[i][1]);
			int colour = players.get(i) ? Win98.NAVY : Win98.SHADOW;
			g.fill(nx - 3, ny - 3, nx + 4, ny + 4, i == hover ? 0xFFC00000 : colour);
			String label = Win98.fit(font, labels.get(i), 70);
			int lx = at[i][0] < graphX + graphW / 2.0D ? nx - 5 - font.width(label) : nx + 6;
			Win98.text(g, font, label, lx, ny - 4, Win98.TEXT);
		}
		g.disableScissor();
	}
}
