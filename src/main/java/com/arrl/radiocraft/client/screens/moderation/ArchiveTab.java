package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.common.moderation.ModerationFormat;
import com.arrl.radiocraft.common.moderation.TransmissionKind;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Archive: past transmissions, filtered by player (as talker, hearer or either), frequency, radio position and
 * dimension, time range, kind, words and flagged only, a page at a time. Picking one shows it in full on the right
 * (talkers, hearers with their signal strength, the radio, data, CW text and notes) with Play and Stop, a progress
 * bar, "as heard by" one of its hearers, Flag or Unflag, Export and Export flagged. With {@code proximity} it is the
 * Proximity tab's archive: plain voice chat only, so no frequency or kind.
 */
class ArchiveTab extends ComputerTab {

	private static final int PAGE = com.arrl.radiocraft.common.moderation.ComputerRequests.MAX_PAGE;
	/** The kind dropdown's groups. */
	private static final List<Set<TransmissionKind>> KIND_GROUPS = List.of(Set.of(),
			EnumSet.of(TransmissionKind.PLAYER_VOICE, TransmissionKind.AUTOPATCH, TransmissionKind.BLOCK_AUDIO),
			EnumSet.of(TransmissionKind.TAPE, TransmissionKind.SERVER_TAPE, TransmissionKind.TAPE_RECORDING),
			EnumSet.of(TransmissionKind.DATA), EnumSet.of(TransmissionKind.CW),
			EnumSet.of(TransmissionKind.COMMERCIAL, TransmissionKind.REPEATED, TransmissionKind.PARROT, TransmissionKind.DTMF));
	private static final String[] KIND_LABELS = { "kinds.all", "kinds.voice", "kinds.tapes", "kinds.data", "kinds.cw", "kinds.notes" };
	/** The role dropdown, in its order, as {@code TransmissionQuery.PlayerRole} ordinals. */
	private static final int[] ROLES = { 2, 0, 1 };

	private final boolean proximity;
	private final W98TextBox player, words, minMhz, maxMhz, radioPos;
	private final W98Dropdown role, kind, range, dimension, hearAs;
	private final W98Checkbox flaggedOnly;
	private final W98Button search, clear, previous, next, play, stop, flag, export, exportFlagged;
	private final W98ListView<CompoundTag> list;
	private int offset = 0, total = 0;
	private CompoundTag detail = null;
	private List<FormattedCharSequence> detailLines = List.of();
	private int detailScroll = 0;
	private int listY, splitX, detailX, detailY, detailW, detailH;
	private boolean wide;
	private boolean asked = false;

	ArchiveTab(ModerationDesk screen, boolean proximity) {
		super(screen);
		this.proximity = proximity;
		Runnable typed = screen::keyClick;
		player = add(new W98TextBox().maxLength(16).hint(() -> tr("archive.anyone")).onKey(typed).onEnter(() -> search(0)));
		role = add(new W98Dropdown(() -> List.of(tr("role.either"), tr("role.talker"), tr("role.hearer"))));
		words = add(new W98TextBox().maxLength(64).hint(() -> tr("archive.words_hint")).onKey(typed).onEnter(() -> search(0)));
		minMhz = add(new W98TextBox().maxLength(10).onKey(typed).onEnter(() -> search(0)));
		maxMhz = add(new W98TextBox().maxLength(10).onKey(typed).onEnter(() -> search(0)));
		kind = add(new W98Dropdown(() -> {
			List<String> out = new ArrayList<>();
			for(String key : KIND_LABELS)
				out.add(tr(key));
			return out;
		}));
		range = add(new W98Dropdown(ComputerTab::rangeLabels));
		range.select(ModerationFormat.Range.LAST_24_HOURS.ordinal());
		flaggedOnly = add(new W98Checkbox(() -> tr("archive.flagged_only")));
		radioPos = add(new W98TextBox().maxLength(40).hint(() -> tr("archive.pos_hint")).onKey(typed).onEnter(() -> search(0)));
		dimension = add(new W98Dropdown(() -> {
			List<String> out = new ArrayList<>();
			out.add(tr("archive.any_world"));
			out.addAll(screen.dimensions);
			return out;
		}));
		search = add(new W98Button(() -> tr("archive.search"), () -> search(0)));
		clear = add(new W98Button(() -> tr("archive.clear"), this::clearFilters));
		previous = add(new W98Button(() -> "<", () -> search(Math.max(0, offset - PAGE))).enabledWhen(() -> offset > 0));
		next = add(new W98Button(() -> ">", () -> search(offset + PAGE)).enabledWhen(() -> offset + PAGE < total));
		list = add(new W98ListView<CompoundTag>(this::cells, proximity ? new int[] { 22, 22, 22, 8, 10 } : new int[] { 20, 18, 14, 14, 18, 7, 9 }, headers()))
				.empty(() -> !screen.status.getBoolean(proximity ? "proximity" : "radio") ? tr(proximity ? "archive.logging_off_proximity" : "archive.logging_off") // Nothing is being kept.
						: tr(asked ? "archive.none" : "archive.press_search"))
				.onSelect(row -> askDetail(row.getLong("id")))
				.colour(row -> row.getBoolean("flagged") ? Win98.RED : row.getBoolean("audio") ? Win98.TEXT : 0xFF404040)
				.sortedBy(this::sortKeys);
		hearAs = add(new W98Dropdown(this::hearAsOptions));
		play = add(new W98Button(() -> tr("archive.play"), this::play).enabledWhen(() -> detail != null && detail.getBoolean("audio")));
		stop = add(new W98Button(() -> tr("archive.stop"), () -> screen.send("stop_play")).enabledWhen(() -> screen.playing));
		flag = add(new W98Button(() -> tr(detail != null && detail.getBoolean("flagged") ? "archive.unflag" : "archive.flag"), this::toggleFlag)
				.enabledWhen(() -> detail != null));
		export = add(new W98Button(() -> tr("archive.export"), () -> idAction("export")).enabledWhen(() -> detail != null && detail.getBoolean("audio")));
		exportFlagged = add(new W98Button(() -> tr("archive.export_flagged"), () -> screen.send("export_flagged")));
	}

	private static String tr(String key, Object... args) {
		return ModerationComputerScreen.tr(key, args);
	}

	private List<java.util.function.Supplier<String>> headers() {
		List<java.util.function.Supplier<String>> out = new ArrayList<>();
		out.add(() -> tr("col.time"));
		out.add(() -> tr(proximity ? "col.speaker" : "col.talker"));
		if(!proximity) {
			out.add(() -> tr("col.kind"));
			out.add(() -> tr("col.frequency"));
		}
		out.add(() -> tr(proximity ? "col.position" : "col.radio"));
		out.add(() -> tr("col.hearing"));
		out.add(() -> tr("col.length"));
		return out;
	}

	@Override
	String title() {
		return tr("tab.archive");
	}

	// -------------------- LAYOUT --------------------

	@Override
	void place() {
		int row1 = y, row2 = y + ROW + GAP, row3 = y + 2 * (ROW + GAP);
		int cx = label(() -> tr("archive.player"), x, row1);
		player.bounds(cx, row1, 76, ROW);
		role.bounds(cx + 78, row1, 62, ROW);
		cx = label(() -> tr("archive.words"), cx + 146, row1);
		words.bounds(cx, row1, x + w - cx, ROW);

		cx = x;
		minMhz.visible = maxMhz.visible = kind.visible = !proximity;
		if(!proximity) {
			cx = label(() -> tr("archive.mhz"), cx, row2);
			minMhz.bounds(cx, row2, 40, ROW);
			cx = label(() -> tr("archive.to"), cx + 43, row2);
			maxMhz.bounds(cx, row2, 40, ROW);
			cx = label(() -> tr("archive.kind"), cx + 46, row2);
			kind.bounds(cx, row2, 58, ROW);
			cx += 64;
		}
		cx = label(() -> tr("archive.when"), cx, row2);
		range.bounds(cx, row2, 80, ROW);
		flaggedOnly.bounds(cx + 86, row2, Math.max(40, x + w - cx - 86), ROW);

		cx = label(() -> tr(proximity ? "archive.where" : "archive.radio"), x, row3);
		radioPos.bounds(cx, row3, 82, ROW);
		cx = label(() -> tr("archive.world"), cx + 86, row3);
		dimension.bounds(cx, row3, 92, ROW);
		cx += 96;
		int searchW = font().width(tr("archive.search")) + 14, clearW = font().width(tr("archive.clear")) + 14;
		search.bounds(cx, row3, searchW, ROW);
		clear.bounds(cx + searchW + GAP, row3, clearW, ROW);
		next.bounds(x + w - 14, row3, 14, ROW);
		previous.bounds(x + w - 30, row3, 14, ROW);

		listY = row3 + ROW + GAP + 1;
		wide = w >= 380;
		int restH = y + h - listY;
		if(wide) {
			splitX = x + (int)(w * 0.56F);
			list.bounds(x, listY, splitX - x - GAP, restH);
			detailX = splitX;
			detailY = listY;
			detailW = x + w - splitX;
			detailH = restH;
		}
		else {
			int listH = restH * 11 / 20;
			list.bounds(x, listY, w, listH);
			detailX = x;
			detailY = listY + listH + GAP;
			detailW = w;
			detailH = y + h - detailY;
		}
		int controls = 3 * (ROW + GAP);
		int cy = detailY + detailH - controls + GAP;
		int ax = label(() -> tr("archive.play_as"), detailX, cy);
		hearAs.bounds(ax, cy, detailX + detailW - ax, ROW);
		cy += ROW + GAP;
		int playW = font().width(tr("archive.play")) + 14, stopW = font().width(tr("archive.stop")) + 14;
		play.bounds(detailX, cy, playW, ROW);
		stop.bounds(detailX + playW + GAP, cy, stopW, ROW);
		progressX = detailX + playW + stopW + 2 * GAP;
		progressY = cy;
		cy += ROW + GAP;
		int flagW = Math.max(font().width(tr("archive.flag")), font().width(tr("archive.unflag"))) + 14;
		int exportW = font().width(tr("archive.export")) + 14;
		flag.bounds(detailX, cy, flagW, ROW);
		export.bounds(detailX + flagW + GAP, cy, exportW, ROW);
		exportFlagged.bounds(detailX + flagW + exportW + 2 * GAP, cy, Math.min(font().width(tr("archive.export_flagged")) + 14, detailX + detailW - (detailX + flagW + exportW + 2 * GAP)), ROW);
		wrapDetail();
	}

	private int progressX, progressY;

	private int textAreaH() {
		return detailH - 3 * (ROW + GAP) - GAP;
	}

	// -------------------- QUERYING --------------------

	@Override
	void shown() {
		if(!asked)
			search(0);
	}

	/** Shows the transmissions of the radio at {@code x, y, z} in {@code dim} (the map's "show in archive"). */
	void filterRadio(String dim, int px, int py, int pz) {
		radioPos.setValue(ModerationFormat.pos(px, py, pz));
		int index = screen.dimensions.indexOf(dim);
		dimension.select(index < 0 ? 0 : index + 1);
		search(0);
	}

	private void clearFilters() {
		player.setValue("");
		words.setValue("");
		minMhz.setValue("");
		maxMhz.setValue("");
		radioPos.setValue("");
		role.select(0);
		kind.select(0);
		dimension.select(0);
		flaggedOnly.setChecked(false);
		range.select(ModerationFormat.Range.LAST_24_HOURS.ordinal());
		search(0);
	}

	private void search(int from) {
		CompoundTag data = new CompoundTag();
		data.putString("player", player.value().trim());
		data.putInt("role", ROLES[role.selected()]);
		data.putString("text", words.value());
		if(!proximity) {
			float low = ModerationFormat.parseMhz(minMhz.value()), high = ModerationFormat.parseMhz(maxMhz.value());
			if((!minMhz.value().isBlank() && Float.isNaN(low)) || (!maxMhz.value().isBlank() && Float.isNaN(high))) {
				screen.showMessage(tr("err.bad_frequency"), true);
				return;
			}
			if(!Float.isNaN(low))
				data.putFloat("fmin", low);
			if(!Float.isNaN(high))
				data.putFloat("fmax", high);
			data.putInt("kinds", ModerationFormat.mask(KIND_GROUPS.get(kind.selected())));
		}
		if(!radioPos.value().isBlank()) {
			int[] pos = ModerationFormat.parsePos(radioPos.value());
			if(pos == null) {
				screen.showMessage(tr("err.bad_position"), true);
				return;
			}
			data.putLong("pos", BlockPos.asLong(pos[0], pos[1], pos[2]));
		}
		int dim = dimension.selected();
		data.putString("dim", dim == 0 || dim > screen.dimensions.size() ? "" : screen.dimensions.get(dim - 1));
		data.putInt("range", range.selected());
		data.putBoolean("flagged", flaggedOnly.checked());
		data.putInt("offset", from);
		data.putInt("limit", PAGE);
		data.putBoolean("prox", proximity);
		asked = true;
		screen.send("query", data);
	}

	private void askDetail(long id) {
		CompoundTag data = new CompoundTag();
		data.putLong("id", id);
		screen.send("detail", data);
	}

	private void idAction(String action) {
		if(detail == null)
			return;
		CompoundTag data = new CompoundTag();
		data.putLong("id", detail.getLong("id"));
		screen.send(action, data);
	}

	private void toggleFlag() {
		if(detail != null)
			idAction(detail.getBoolean("flagged") ? "unflag" : "flag");
	}

	private void play() {
		if(detail == null)
			return;
		CompoundTag data = new CompoundTag();
		data.putLong("id", detail.getLong("id"));
		if(hearAs.selected() > 0)
			data.putInt("hearer", hearAs.selected() - 1);
		screen.send("play", data);
	}

	/** Clean, then each logged hearer: as heard is offered for radio voice only (not tapes or voice chat). */
	private List<String> hearAsOptions() {
		List<String> out = new ArrayList<>();
		out.add(tr("archive.clean"));
		if(detail == null || proximity || !voiceKind(detail.getString("kind")))
			return out;
		for(CompoundTag hearer : rows(detail, "hearerList"))
			out.add(tr("archive.heard_by", hearer.getString("name").isEmpty() ? ModerationComputerScreen.radioName(hearer.getString("radio")) : hearer.getString("name"),
					Math.round(hearer.getFloat("strength") * 100.0F)));
		return out;
	}

	private static boolean voiceKind(String kind) {
		return kind.equals(TransmissionKind.PLAYER_VOICE.id()) || kind.equals(TransmissionKind.AUTOPATCH.id()) || kind.equals(TransmissionKind.BLOCK_AUDIO.id());
	}

	@Override
	void receive(String kind, CompoundTag data) {
		switch(kind) {
			case "page" -> {
				if(data.getBoolean("prox") != proximity)
					return;
				offset = data.getInt("offset");
				total = data.getInt("total");
				list.setItems(rows(data, "rows"), row -> row.getLong("id"));
			}
			case "detail" -> {
				boolean isProximity = data.getString("kind").equals(TransmissionKind.PROXIMITY.id());
				if(isProximity != proximity)
					return;
				if(detail == null || detail.getLong("id") != data.getLong("id")) {
					hearAs.select(0);
					detailScroll = 0;
				}
				detail = data;
				wrapDetail();
			}
			case "flagged" -> {
				if(detail != null && detail.getLong("id") == data.getLong("id")) {
					askDetail(data.getLong("id"));
					search(offset);
				}
			}
			default -> {}
		}
	}

	/** What each column sorts by (within the page shown): the text shown, or the number behind it. */
	private Object[] sortKeys(CompoundTag row) {
		String[] cells = cells(row);
		if(proximity)
			return new Object[] { row.getLong("start"), cells[1], cells[2], row.getInt("hearers"), row.getLong("length") };
		return new Object[] { row.getLong("start"), cells[1], cells[2], row.getFloat("hz"), cells[4], row.getInt("hearers"), row.getLong("length") };
	}

	private String[] cells(CompoundTag row) {
		String time = ModerationFormat.time(row.getLong("start"), ZoneId.systemDefault());
		String talkers = row.getString("talkers").isEmpty() ? "-" : row.getString("talkers");
		String where = row.contains("pos") ? LiveTab.pos(row.getLong("pos")) : "-";
		String length = ModerationFormat.duration(row.getLong("length")) + (row.getBoolean("flagged") ? " F" : "");
		if(proximity)
			return new String[] { time, talkers, where, Integer.toString(row.getInt("hearers")), length };
		return new String[] { time, talkers, tr("kind." + row.getString("kind")), ModerationFormat.mhz(row.getFloat("hz")),
				ModerationComputerScreen.radioName(row.getString("radio")), Integer.toString(row.getInt("hearers")), length };
	}

	// -------------------- THE DETAIL PANE --------------------

	/** The selected transmission's lines, wrapped to the pane. */
	private void wrapDetail() {
		if(detail == null || detailW <= 12) {
			detailLines = List.of();
			return;
		}
		Font font = font();
		int width = detailW - 10;
		List<String> lines = new ArrayList<>();
		CompoundTag d = detail;
		lines.add("#" + d.getLong("id") + "  " + ModerationFormat.time(d.getLong("start"), ZoneId.systemDefault()) + "  " + ModerationFormat.duration(d.getLong("length")));
		lines.add(tr("detail.kind", tr("kind." + d.getString("kind"))) + (d.getBoolean("flagged") ? "  " + tr("detail.flagged") : ""));
		List<String> names = new ArrayList<>();
		for(CompoundTag talker : rows(d, "talkerList"))
			names.add(talker.getString("name") + (talker.getString("call").isEmpty() ? "" : " (" + talker.getString("call") + ")"));
		lines.add(tr("detail.talkers", names.isEmpty() ? "-" : String.join(", ", names)));
		if(!proximity)
			lines.add(tr("detail.frequency", ModerationFormat.mhz(d.getFloat("hz")), d.getString("mode"), d.getString("band").isEmpty() ? "-" : d.getString("band"))
					+ (d.getFloat("ctcss") > 0.0F ? "  " + tr("detail.ctcss", String.format(Locale.ROOT, "%.1f", d.getFloat("ctcss"))) : ""));
		lines.add(tr("detail.radio", ModerationComputerScreen.radioName(d.getString("radio")), d.contains("pos") ? LiveTab.pos(d.getLong("pos")) : "-", d.getString("dim")));
		if(d.contains("antenna") && d.getLong("antenna") != d.getLong("pos"))
			lines.add(tr("detail.antenna", LiveTab.pos(d.getLong("antenna"))));
		lines.add(tr("detail.hearers", d.getInt("hearerCount")));
		for(CompoundTag hearer : rows(d, "hearerList"))
			lines.add("  " + (hearer.getString("name").isEmpty() ? "" : hearer.getString("name") + " - ") + ModerationComputerScreen.radioName(hearer.getString("radio"))
					+ (hearer.contains("pos") ? " " + LiveTab.pos(hearer.getLong("pos")) : "") + ", " + Math.round(hearer.getFloat("strength") * 100.0F) + "%");
		if(d.getInt("hearerCount") > rows(d, "hearerList").size())
			lines.add("  " + tr("detail.more", d.getInt("hearerCount") - rows(d, "hearerList").size()));
		if(!d.getString("fullText").isEmpty())
			lines.add(tr(d.getString("kind").equals(TransmissionKind.CW.id()) ? "detail.cw" : "detail.text", d.getString("fullText")));
		List<String> packets = strings(d, "packets");
		if(!packets.isEmpty()) {
			lines.add(tr("detail.packets", d.getInt("packetCount")));
			for(String packet : packets)
				lines.add("  " + packet);
		}
		if(d.getLong("linked") != 0L)
			lines.add(tr("detail.linked", d.getLong("linked")));
		if(d.contains("tape"))
			lines.add(tr("detail.tape", d.getString("tape").substring(0, 8)));
		if(d.getBoolean("audio"))
			lines.add(tr("detail.frames", d.getInt("frames")));
		else
			lines.add(tr("detail.no_audio"));
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for(String line : lines)
			wrapped.addAll(font.split(Component.literal(line), width));
		detailLines = wrapped;
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		int areaH = textAreaH();
		Win98.sunken(g, detailX, detailY, detailW, areaH, Win98.FIELD);
		if(detail == null)
			Win98.text(g, font, Win98.fit(font, tr("archive.pick"), detailW - 10), detailX + 5, detailY + 5, Win98.GREY_TEXT);
		else {
			int visible = Math.max(1, (areaH - 8) / 10);
			detailScroll = ModerationLayout.clampFirst(detailScroll, visible, detailLines.size());
			for(int i = 0; i < visible && detailScroll + i < detailLines.size(); i++)
				g.drawString(font, detailLines.get(detailScroll + i), detailX + 5, detailY + 4 + i * 10, Win98.TEXT, false);
			if(detailLines.size() > visible) // There is more: a small arrow in the corner.
				Win98.arrowDown(g, detailX + detailW - 8, detailY + areaH - 8, 5, Win98.SHADOW);
		}
		boolean ours = detail != null && screen.playingId == detail.getLong("id");
		double fraction = ours && screen.playLength > 0L ? (double)screen.playPosition / screen.playLength : 0.0D;
		int barW = detailX + detailW - progressX;
		if(barW > 20) {
			Win98.progress(g, progressX, progressY, barW, ROW, fraction);
			if(ours && screen.playing) {
				String time = ModerationFormat.duration(screen.playPosition) + " / " + ModerationFormat.duration(screen.playLength);
				if(font.width(time) < barW - 6)
					Win98.text(g, font, time, progressX + (barW - font.width(time)) / 2, progressY + 3, fraction > 0.5D ? Win98.SELECTED_TEXT : Win98.TEXT);
			}
		}
		super.render(g, font, mouseX, mouseY);
		String pageText = total == 0 ? tr("archive.no_results") : tr("archive.page", offset + 1, Math.min(total, offset + PAGE), total);
		int row3 = y + 2 * (ROW + GAP);
		int right = x + w - 34;
		int left = clear.x + clear.w + 6;
		if(font.width(pageText) <= right - left)
			Win98.text(g, font, pageText, right - font.width(pageText), row3 + 3, Win98.TEXT);
	}

	@Override
	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if(mouseX >= detailX && mouseX < detailX + detailW && mouseY >= detailY && mouseY < detailY + textAreaH()) {
			detailScroll -= (int)Math.signum(amount) * 2;
			return true;
		}
		return false;
	}
}
