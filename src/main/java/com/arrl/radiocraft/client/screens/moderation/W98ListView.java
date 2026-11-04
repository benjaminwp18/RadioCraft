package com.arrl.radiocraft.client.screens.moderation;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A report-style list view: raised column headers (click one to sort by it, if the list allows), rows in a sunken white
 * field with the selected one in navy, and a scrollbar. Rows are items of {@code T} shown through {@link #cells}; the
 * mouse wheel, the scrollbar's buttons and its thumb scroll it.
 */
class W98ListView<T> extends W98Component {

	static final int ROW = 11;
	private static final int HEADER = 13;
	private static final int BAR = 11;

	private final List<Supplier<String>> headers;
	private final int[] weights;
	private final Function<T, String[]> cells;
	private List<T> items = new ArrayList<>();
	private int selected = -1;
	private int first = 0;
	private Consumer<T> onSelect = item -> {};
	private Consumer<T> onDoubleClick = item -> {};
	private IntConsumer onSort = null;
	/** Each row's sort key per column, when the list sorts itself ({@link #sortedBy}). */
	private Function<T, Object[]> sortKeys = null;
	private Function<T, Integer> colour = item -> Win98.TEXT;
	private int sortColumn = -1;
	private boolean sortDescending = false;
	private int pressedHeader = -1;
	private boolean draggingThumb = false;
	private int dragOffset = 0;
	private long lastClickMs = 0L;
	private int lastClickRow = -1;
	private Supplier<String> empty = () -> "";

	/** A list showing {@code cells} of each item under {@code headers}, the columns sharing its width by {@code weights}. */
	W98ListView(Function<T, String[]> cells, int[] weights, List<Supplier<String>> headers) {
		this.cells = cells;
		this.weights = weights;
		this.headers = List.copyOf(headers);
	}

	W98ListView<T> onSelect(Consumer<T> action) {
		onSelect = action;
		return this;
	}

	W98ListView<T> onDoubleClick(Consumer<T> action) {
		onDoubleClick = action;
		return this;
	}

	/** Lets the user sort: {@code action} gets the column clicked, and the list shows an arrow on it. */
	W98ListView<T> sortable(IntConsumer action) {
		onSort = action;
		return this;
	}

	/**
	 * Lets the user sort by clicking a header, the list sorting itself: by each row's {@code keys} in that column
	 * (numbers as numbers, anything else as text, see {@link ModerationLayout#compareKeys}). New rows keep the order.
	 */
	W98ListView<T> sortedBy(Function<T, Object[]> keys) {
		sortKeys = keys;
		onSort = column -> {
			T was = selectedItem();
			sortItems();
			selected = was == null ? -1 : items.indexOf(was);
		};
		return this;
	}

	private void sortItems() {
		if(sortKeys == null || sortColumn < 0)
			return;
		int column = sortColumn;
		java.util.Comparator<T> order = (a, b) -> ModerationLayout.compareKeys(key(a, column), key(b, column));
		items.sort(sortDescending ? order.reversed() : order);
	}

	private Object key(T item, int column) {
		Object[] keys = sortKeys.apply(item);
		return column < keys.length ? keys[column] : null;
	}

	W98ListView<T> colour(Function<T, Integer> colour) {
		this.colour = colour;
		return this;
	}

	/** What the list says while it has no rows. */
	W98ListView<T> empty(Supplier<String> text) {
		empty = text;
		return this;
	}

	void setSort(int column, boolean descending) {
		sortColumn = column;
		sortDescending = descending;
	}

	int sortColumn() {
		return sortColumn;
	}

	boolean sortDescending() {
		return sortDescending;
	}

	List<T> items() {
		return items;
	}

	/** New rows; the selection follows the row with the same {@code key} as the selected one, if it is still there. */
	void setItems(List<T> rows, Function<T, Object> key) {
		T was = selectedItem();
		Object wasKey = was == null ? null : key.apply(was);
		items = new ArrayList<>(rows);
		sortItems();
		selected = -1;
		if(wasKey != null)
			for(int i = 0; i < items.size(); i++)
				if(wasKey.equals(key.apply(items.get(i)))) {
					selected = i;
					break;
				}
		first = ModerationLayout.clampFirst(first, visibleRows(), items.size());
	}

	/** New rows, the selection kept on an equal row. */
	void setItems(List<T> rows) {
		setItems(rows, item -> item);
	}

	T selectedItem() {
		return selected >= 0 && selected < items.size() ? items.get(selected) : null;
	}

	void select(int index) {
		selected = index >= 0 && index < items.size() ? index : -1;
		first = ModerationLayout.scrollTo(selected, first, visibleRows(), items.size());
	}

	private int visibleRows() {
		return Math.max(1, (h - HEADER - 4) / ROW);
	}

	private int[] columnWidths() {
		return ModerationLayout.columns(w - BAR - 4, weights, 16);
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		if(!visible)
			return;
		Win98.sunken(g, x, y, w, h, Win98.FIELD);
		int[] widths = columnWidths();
		int cx = x + 2;
		for(int c = 0; c < headers.size(); c++) { // Column headers.
			boolean down = pressedHeader == c && mouseX >= cx && mouseX < cx + widths[c] && mouseY >= y + 2 && mouseY < y + 2 + HEADER;
			if(down)
				Win98.pressed(g, cx, y + 2, widths[c], HEADER);
			else
				Win98.raised(g, cx, y + 2, widths[c], HEADER);
			String label = Win98.fit(font, headers.get(c).get(), widths[c] - (c == sortColumn ? 14 : 6));
			Win98.text(g, font, label, cx + 3 + (down ? 1 : 0), y + 4 + (down ? 1 : 0), Win98.TEXT);
			if(c == sortColumn) {
				int ax = cx + widths[c] - 7;
				if(sortDescending)
					Win98.arrowDown(g, ax, y + 6, 5, Win98.SHADOW);
				else
					Win98.arrowUp(g, ax, y + 6, 5, Win98.SHADOW);
			}
			cx += widths[c];
		}
		int top = y + 2 + HEADER;
		int visible = visibleRows();
		first = ModerationLayout.clampFirst(first, visible, items.size());
		if(items.isEmpty()) {
			String text = empty.get();
			if(!text.isEmpty()) { // Wrapped, for the longer notices (logging disabled).
				int line = 0;
				for(net.minecraft.util.FormattedCharSequence part : font.split(net.minecraft.network.chat.Component.literal(text), w - BAR - 10))
					g.drawString(font, part, x + 5, top + 4 + 10 * line++, Win98.GREY_TEXT, false);
			}
		}
		for(int r = 0; r < visible && first + r < items.size(); r++) {
			int index = first + r;
			T item = items.get(index);
			int ry = top + r * ROW;
			boolean isSelected = index == selected;
			if(isSelected)
				g.fill(x + 2, ry, x + w - BAR - 2, ry + ROW, Win98.SELECTION);
			String[] row = cells.apply(item);
			int colourOf = isSelected ? Win98.SELECTED_TEXT : colour.apply(item);
			int tx = x + 2;
			for(int c = 0; c < widths.length && c < row.length; c++) {
				Win98.text(g, font, Win98.fit(font, row[c], widths[c] - 5), tx + 3, ry + 2, colourOf);
				tx += widths[c];
			}
		}
		Win98.scrollbar(g, x + w - BAR - 2, y + 2, BAR, h - 4, first, visible, items.size());
	}

	@Override
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if(button != 0 || !contains(mouseX, mouseY))
			return false;
		int barX = x + w - BAR - 2;
		int visible = visibleRows();
		if(mouseX >= barX) { // The scrollbar.
			int track = h - 4 - 2 * BAR;
			if(mouseY < y + 2 + BAR)
				first = ModerationLayout.clampFirst(first - 1, visible, items.size());
			else if(mouseY >= y + h - 2 - BAR)
				first = ModerationLayout.clampFirst(first + 1, visible, items.size());
			else {
				int[] thumb = ModerationLayout.thumb(track, first, visible, items.size(), 8);
				int at = (int)mouseY - (y + 2 + BAR);
				if(at >= thumb[0] && at < thumb[0] + thumb[1]) {
					draggingThumb = true;
					dragOffset = at - thumb[0];
				}
				else // Paging, as a click on the track does.
					first = ModerationLayout.clampFirst(first + (at < thumb[0] ? -visible : visible), visible, items.size());
			}
			return true;
		}
		if(mouseY < y + 2 + HEADER) { // A header: sort on release.
			int[] widths = columnWidths();
			int cx = x + 2;
			for(int c = 0; c < widths.length; c++) {
				if(mouseX >= cx && mouseX < cx + widths[c])
					pressedHeader = onSort == null ? -1 : c;
				cx += widths[c];
			}
			return true;
		}
		int row = first + (int)((mouseY - (y + 2 + HEADER)) / ROW);
		if(row >= 0 && row < items.size()) {
			long now = System.currentTimeMillis();
			boolean doubleClick = row == lastClickRow && now - lastClickMs < 400L;
			lastClickMs = now;
			lastClickRow = row;
			selected = row;
			onSelect.accept(items.get(row));
			if(doubleClick)
				onDoubleClick.accept(items.get(row));
		}
		return true;
	}

	@Override
	boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingThumb = false;
		if(pressedHeader < 0)
			return false;
		int column = pressedHeader;
		pressedHeader = -1;
		if(contains(mouseX, mouseY) && mouseY < y + 2 + HEADER && onSort != null) {
			if(column == sortColumn)
				sortDescending = !sortDescending;
			else {
				sortColumn = column;
				sortDescending = false;
			}
			onSort.accept(column);
		}
		return true;
	}

	@Override
	boolean mouseDragged(double mouseX, double mouseY, int button) {
		if(!draggingThumb)
			return false;
		int track = h - 4 - 2 * BAR;
		first = ModerationLayout.firstForThumb((int)mouseY - (y + 2 + BAR) - dragOffset, track, visibleRows(), items.size(), 8);
		return true;
	}

	@Override
	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if(!contains(mouseX, mouseY))
			return false;
		first = ModerationLayout.clampFirst(first - (int)Math.signum(amount) * 3, visibleRows(), items.size());
		return true;
	}

	/** The item under the mouse, or null. */
	T itemAt(double mouseX, double mouseY) {
		if(!contains(mouseX, mouseY) || mouseX >= x + w - BAR - 2 || mouseY < y + 2 + HEADER)
			return null;
		int row = first + (int)((mouseY - (y + 2 + HEADER)) / ROW);
		return row >= 0 && row < items.size() ? items.get(row) : null;
	}
}
