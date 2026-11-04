package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.common.moderation.ModerationFormat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Live: who is on the air now (talker, kind, frequency, radio, where, how many hear it, for how long), refreshed twice
 * a second. Pick a row and Listen to talker to follow that talker live, or Listen (or double-click it) to tune in to
 * its frequency and hear whatever is sent there; or pick any online player and hear what their radios receive. With {@code proximity} it is the Proximity tab's live view: plain voice chat, and Listen hears that player's
 * voice chat wherever they are. The bell rings when the player being followed keys up. Freeze holds the list still to
 * scroll through and read (the bell still rings).
 */
class LiveTab extends ComputerTab {

	private final boolean proximity;
	private final W98ListView<CompoundTag> list;
	private final W98Dropdown player;
	/** Shows the world's own stations (commercial modules, server tapes, repeats) too; radio Live only. */
	private final W98Checkbox system;
	/** Holds the list still so it can be scrolled and read; the bell still rings. */
	private final W98Checkbox freeze;
	/** Listens to the selected transmission's frequency (radio Live only). */
	private final W98Button broadcast;
	private final Set<Long> seen = new HashSet<>();
	private boolean firstAnswer = true;
	private long serverNow = System.currentTimeMillis();

	LiveTab(ModerationDesk screen, boolean proximity) {
		super(screen);
		this.proximity = proximity;
		list = add(new W98ListView<CompoundTag>(this::cells, new int[] { 22, 13, 13, 18, 18, 8, 8 },
				ModerationComputerScreen.columns("col.talker", "col.kind", "col.frequency", "col.radio", "col.position", "col.hearing", "col.time")))
				.empty(() -> ModerationComputerScreen.tr(proximity ? "live.empty_proximity" : "live.empty"))
				.onDoubleClick(row -> {
					if(proximity)
						listenToRow();
					else
						listenToBroadcast();
				})
				.sortedBy(this::sortKeys);
		add(new W98Button(() -> ModerationComputerScreen.tr(proximity ? "live.listen_voice" : "live.listen"), this::listenToRow)
				.enabledWhen(() -> talkerUuid(list.selectedItem()) != null));
		add(new W98Button(() -> ModerationComputerScreen.tr("live.stop"), () -> screen.send("stop_listen"))
				.enabledWhen(() -> !screen.listeningMode().isEmpty()));
		player = add(new W98Dropdown(() -> screen.players));
		add(new W98Button(() -> ModerationComputerScreen.tr(proximity ? "live.voice_of" : "live.hear_through"), this::listenToPlayer)
				.enabledWhen(() -> !screen.players.isEmpty()));
		system = proximity ? null : add(new W98Checkbox(() -> ModerationComputerScreen.tr("live.system")).onChange(this::ask));
		freeze = add(new W98Checkbox(() -> ModerationComputerScreen.tr("live.freeze")).onChange(this::ask));
		broadcast = proximity ? null : add(new W98Button(() -> ModerationComputerScreen.tr("live.listen_frequency"), this::listenToBroadcast)
				.enabledWhen(() -> list.selectedItem() != null && list.selectedItem().getFloat("hz") > 0.0F));
	}

	@Override
	String title() {
		return ModerationComputerScreen.tr(proximity ? "tab.live_proximity" : "tab.live");
	}

	@Override
	void place() {
		int bottom = y + h - ROW;
		list.bounds(x, y + ROW + GAP, w, bottom - GAP - (y + ROW + GAP));
		W98Button listen = (W98Button)components.get(1), stop = (W98Button)components.get(2), through = (W98Button)components.get(4);
		int bx = x;
		int listenW = font().width(ModerationComputerScreen.tr(proximity ? "live.listen_voice" : "live.listen")) + 14;
		listen.bounds(bx, bottom, listenW, ROW);
		bx += listenW + GAP;
		if(broadcast != null) {
			int broadcastW = font().width(ModerationComputerScreen.tr("live.listen_frequency")) + 14;
			broadcast.bounds(bx, bottom, broadcastW, ROW);
			bx += broadcastW + GAP;
		}
		int stopW = font().width(ModerationComputerScreen.tr("live.stop")) + 14;
		stop.bounds(bx, bottom, stopW, ROW);
		bx += stopW + GAP * 4;
		bx = label(() -> ModerationComputerScreen.tr("live.player"), bx, bottom);
		int throughW = font().width(ModerationComputerScreen.tr(proximity ? "live.voice_of" : "live.hear_through")) + 14;
		// Freeze at the right end of the row, the player dropdown giving way to it first, then its own label.
		int freezeW = font().width(ModerationComputerScreen.tr("live.freeze")) + 16;
		int dropW = Math.max(60, Math.min(110, x + w - bx - throughW - GAP - freezeW - GAP * 4));
		player.bounds(bx, bottom, dropW, ROW);
		through.bounds(bx + dropW + GAP, bottom, throughW, ROW);
		int afterThrough = bx + dropW + GAP + throughW + GAP * 4;
		freezeW = Math.max(11, Math.min(freezeW, x + w - afterThrough));
		freeze.bounds(x + w - freezeW, bottom, freezeW, ROW);
		if(system != null) // Over the list, between the heading and the listening line (moved each frame in render).
			system.bounds(x + w / 3, y, font().width(ModerationComputerScreen.tr("live.system")) + 16, ROW);
	}

	private String[] cells(CompoundTag row) {
		List<CompoundTag> talkers = rows(row, "talkers");
		String talker = talkers.isEmpty() ? "-" : talkers.getFirst().getString("name") + (talkers.getFirst().getString("call").isEmpty() ? "" : " (" + talkers.getFirst().getString("call") + ")")
				+ (talkers.size() > 1 ? " +" + (talkers.size() - 1) : "");
		String where = row.contains("pos") ? pos(row.getLong("pos")) : "-";
		return new String[] { talker, ModerationComputerScreen.tr("kind." + row.getString("kind")), ModerationFormat.mhz(row.getFloat("hz")) + " " + row.getString("mode"),
				ModerationComputerScreen.radioName(row.getString("radio")), where, Integer.toString(row.getInt("hearers")),
				ModerationFormat.duration(serverNow - row.getLong("start")) };
	}

	/** What each column sorts by: the text shown, or the number behind it. */
	private Object[] sortKeys(CompoundTag row) {
		String[] cells = cells(row);
		return new Object[] { cells[0], cells[1], row.getFloat("hz"), cells[3], cells[4], row.getInt("hearers"), serverNow - row.getLong("start") };
	}

	static String pos(long packed) {
		BlockPos pos = BlockPos.of(packed);
		return ModerationFormat.pos(pos.getX(), pos.getY(), pos.getZ());
	}

	private static String talkerUuid(CompoundTag row) {
		if(row == null)
			return null;
		List<CompoundTag> talkers = rows(row, "talkers");
		return talkers.isEmpty() ? null : talkers.getFirst().getString("uuid");
	}

	private void listenToRow() {
		CompoundTag row = list.selectedItem();
		String uuid = talkerUuid(row);
		if(uuid == null)
			return;
		CompoundTag data = new CompoundTag();
		data.putString("uuid", uuid);
		data.putString("name", rows(row, "talkers").getFirst().getString("name"));
		data.putString("mode", proximity ? "voice" : "sends");
		screen.send("listen", data);
	}

	/** Tunes in to the selected row's frequency: everything sent there, whoever or whatever sends it. */
	private void listenToBroadcast() {
		CompoundTag row = list.selectedItem();
		if(row == null || row.getFloat("hz") <= 0.0F)
			return;
		CompoundTag data = new CompoundTag();
		data.putFloat("hz", row.getFloat("hz"));
		data.putString("mode", "frequency");
		screen.send("listen", data);
	}

	private void listenToPlayer() {
		CompoundTag data = new CompoundTag();
		data.putString("name", player.selectedLabel());
		data.putString("mode", proximity ? "voice" : "hears");
		screen.send("listen", data);
	}

	@Override
	void shown() {
		ask();
	}

	@Override
	void tick(int ticks) {
		if(ticks % 10 == 0) // Twice a second.
			ask();
	}

	private void ask() {
		CompoundTag data = new CompoundTag();
		data.putBoolean("prox", proximity);
		data.putBoolean("system", system != null && system.checked());
		screen.send("live", data);
	}

	@Override
	void receive(String kind, CompoundTag data) {
		if(!kind.equals("live") || data.getBoolean("prox") != proximity)
			return;
		List<CompoundTag> rows = rows(data, "rows");
		if(!freeze.checked()) {
			serverNow = data.getLong("now");
			list.setItems(rows, row -> row.getLong("id"));
		}
		// The bell: the player being followed has keyed up (a transmission of theirs not seen before).
		String mode = screen.listeningMode();
		String target = screen.listening.getString("uuid");
		boolean follows = proximity ? mode.equals("voice") : mode.equals("sends");
		for(CompoundTag row : rows) {
			boolean isNew = seen.add(row.getLong("id"));
			if(isNew && !firstAnswer && follows)
				for(CompoundTag talker : rows(row, "talkers"))
					if(talker.getString("uuid").equals(target))
						screen.bell();
		}
		firstAnswer = false;
		if(seen.size() > 2000)
			seen.clear();
	}

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		String mode = screen.listeningMode();
		String state = mode.isEmpty() ? ModerationComputerScreen.tr("live.not_listening")
				: ModerationComputerScreen.tr("live.listening_" + mode, screen.listening.getString("name"));
		String heading = Win98.fit(font, ModerationComputerScreen.tr(proximity ? "live.heading_proximity" : "live.heading"), w / 2);
		String shown = Win98.fit(font, state, w / 2 - 4);
		int sx = x + w - font.width(shown);
		if(system != null) { // Centred in the gap between the heading and the listening line, which changes length.
			int gapLeft = x + font.width(heading) + 8, gapRight = sx - 12;
			int systemW = Math.min(font.width(ModerationComputerScreen.tr("live.system")) + 16, Math.max(0, gapRight - gapLeft));
			system.bounds(gapLeft + (gapRight - gapLeft - systemW) / 2, y, systemW, ROW);
		}
		super.render(g, font, mouseX, mouseY);
		Win98.text(g, font, heading, x, y + 3, Win98.TEXT);
		if(!mode.isEmpty())
			g.fill(sx - 8, y + 4, sx - 3, y + 9, Win98.GREEN); // A lit lamp while listening.
		Win98.text(g, font, shown, sx, y + 3, mode.isEmpty() ? Win98.GREY_TEXT : Win98.TEXT);
	}
}
