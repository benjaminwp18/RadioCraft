package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.client.screens.TerminalWhirr;
import com.arrl.radiocraft.common.menus.ModerationComputerMenu;
import com.arrl.radiocraft.common.moderation.ModerationFormat;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Radio Moderation Computer's screen (maintainer's design, 2026-11-04): a Windows 98 style window over the
 * moderation log, drawn with plain fills and the vanilla font. A navy title bar with a close box, tabs along the top
 * (Live, Archive, Map, Links, Tapes, Proximity, Access) and a status bar along the bottom: whether recording is on,
 * the storage used against the cap, and the last message.
 * <p>
 * It fills most of the window, up to a comfortable size; on a small window at a large GUI scale it is drawn smaller as
 * a whole ({@link ModerationLayout#fitScale}) rather than cut off, and works in its own unscaled coordinates, as
 * {@link com.arrl.radiocraft.client.screens.StationPanelScreen} does. Everything shown comes from the server, asked for
 * with {@link com.arrl.radiocraft.common.network.serverbound.SStationPacket} requests and answered with {@link #receive}.
 * <p>
 * Sounds are the H19's, kept quiet: its fan spinning up while the screen is open, a key click for each key typed in a
 * text box, and its bell when a player being listened to keys up.
 * <p>
 * The tabs and what the server said live on a {@link ModerationDesk}, kept for a while after the screen closes, so
 * opening it again carries on where it was left; the desk also copies the window to the computer's monitor.
 */
public final class ModerationComputerScreen extends AbstractContainerScreen<ModerationComputerMenu> {

	/** The smallest layout that works, and the largest worth having, in GUI pixels. */
	private static final int MIN_W = 540, MIN_H = 250, MAX_W = 600, MAX_H = 380;
	private static final int TITLE_H = 14, TAB_H = 14, STATUS_H = 14;
	private static final long MESSAGE_MS = 12_000L;

	private float scale = 1.0F;
	ModerationDesk desk;
	private List<ComputerTab> tabs;
	@Nullable
	private W98Component focused;
	private boolean closeHeld = false;
	@Nullable
	private TerminalWhirr whirr;
	/** Starting up, drawn over the desktop until it is done (see {@link BootSequence}). */
	private BootSequence boot;
	private boolean opened = false;
	private int ticks = 0;

	public ModerationComputerScreen(ModerationComputerMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
		desk = ModerationDesk.open(menu.getBlockPos(), this);
		tabs = desk.tabs;
	}

	/** {@code screen.radiocraft.moderation_computer.<key>}, formatted. */
	static String tr(String key, Object... args) {
		return Component.translatable(Radiocraft.translationKey("screen", "moderation_computer." + key), args).getString();
	}

	/** Column headers for a list, from their keys (translated as they are drawn, so a language change shows at once). */
	static List<java.util.function.Supplier<String>> columns(String... keys) {
		List<java.util.function.Supplier<String>> out = new ArrayList<>();
		for(String key : keys)
			out.add(() -> tr(key));
		return out;
	}

	/** A radio kind as logged ("vhf_base_station", "handheld") as a name: the block's, or the computer's own word for it. */
	static String radioName(String kind) {
		if(kind.isEmpty())
			return "-";
		String block = "block.radiocraft." + kind, own = Radiocraft.translationKey("screen", "moderation_computer.radio." + kind);
		if(I18n.exists(block))
			return I18n.get(block);
		return I18n.exists(own) ? I18n.get(own) : kind.replace('_', ' ');
	}

	/** An answer from the server for the open screen (see {@code CModerationPacket}). Client thread. */
	public static void receive(String kind, CompoundTag data) {
		if(Minecraft.getInstance().screen instanceof ModerationComputerScreen screen)
			screen.onReply(kind, data);
	}

	// -------------------- LAYOUT --------------------

	@Override
	protected void init() {
		Window window = Minecraft.getInstance().getWindow();
		int guiW = window.getGuiScaledWidth(), guiH = window.getGuiScaledHeight();
		scale = ModerationLayout.fitScale(guiW, guiH, MIN_W + 8, MIN_H + 8);
		width = (int)(guiW / scale);
		height = (int)(guiH / scale);
		imageWidth = Math.min(MAX_W, width - 8);
		imageHeight = Math.min(MAX_H, height - 8);
		super.init();
		layoutTabs();
		if(!opened) {
			opened = true;
			desk.send("open");
			whirr = TerminalWhirr.start(0.12F); // The fan spinning up.
			if(!desk.resumed()) // Left on: no boot, it shows what it showed.
				boot = new BootSequence(menu.getBlockPos());
			tab().shown();
		}
	}

	private void layoutTabs() {
		int panelX = leftPos + 3, panelY = topPos + 3 + TITLE_H + 2 + TAB_H, panelW = imageWidth - 6, panelH = imageHeight - (panelY - topPos) - STATUS_H - 5;
		for(ComputerTab tab : tabs)
			tab.layout(panelX + 6, panelY + 6, panelW - 12, panelH - 12);
		for(ComputerTab tab : tabs)
			for(W98Component component : tab.allComponents())
				if(component instanceof W98Dropdown dropdown)
					dropdown.limitY = panelY + panelH;
	}

	/** Starts afresh: listening and playback stopped, new tabs, a full boot from the BIOS. */
	void reboot() {
		closePopups();
		setFocus(null);
		desk.send("stop_listen");
		desk.send("stop_play");
		desk = ModerationDesk.replace(desk, this);
		tabs = desk.tabs;
		layoutTabs();
		boot = BootSequence.cold(menu.getBlockPos());
		desk.send("open");
		tab().shown();
	}

	private ComputerTab tab() {
		return tabs.get(desk.active);
	}

	void selectTab(int index) {
		if(index == desk.active || index < 0 || index >= tabs.size())
			return;
		closePopups();
		setFocus(null);
		desk.active = index;
		tab().shown();
	}

	@Override
	public void removed() {
		if(whirr != null)
			whirr.finish();
		closePopups();
		setFocus(null);
		desk.closed();
		super.removed();
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		ticks++;
		tab().tick(ticks);
		if(ticks % 40 == 0)
			desk.send("status");
	}

	// -------------------- ANSWERS --------------------

	private void onReply(String kind, CompoundTag data) {
		switch(kind) {
			case "status" -> {
				desk.status = data;
				desk.players = ComputerTab.strings(data, "players");
				List<String> dims = ComputerTab.strings(data, "dims");
				if(!dims.isEmpty())
					desk.dimensions = dims;
			}
			case "access" -> desk.access = data;
			case "listening" -> desk.listening = data;
			case "message" -> desk.showMessage(tr("msg." + data.getString("key"), data.getString("arg")), false);
			case "error" -> desk.showMessage(tr("err." + data.getString("key"), data.getString("arg")), true);
			case "denied" -> desk.showMessage(tr("err.denied"), true);
			case "playing" -> {
				desk.playing = true;
				desk.playingId = data.getLong("id");
				desk.playLength = data.getLong("length");
				desk.playPosition = 0L;
			}
			case "progress" -> {
				desk.playingId = data.getLong("id");
				desk.playPosition = data.getLong("position");
				desk.playLength = data.getLong("length");
			}
			case "play_done", "play_stopped" -> {
				desk.playing = false;
				desk.playPosition = 0L;
			}
			case "play_error" -> {
				desk.playing = false;
				desk.showMessage(tr("err.play_" + data.getString("key")), true);
			}
			case "exported" -> {
				int files = data.getList("files", net.minecraft.nbt.Tag.TAG_STRING).size();
				if(data.getBoolean("unknown"))
					desk.showMessage(tr("err.unknown_transmission", ""), true);
				else if(files == 0)
					desk.showMessage(tr("msg.exported_none"), false);
				else
					desk.showMessage(tr("msg.exported", files, files == 1 ? ComputerTab.strings(data, "files").getFirst() : data.getString("folder")), false);
			}
			default -> {}
		}
		for(ComputerTab tab : tabs)
			tab.receive(kind, data);
	}

	// -------------------- DRAWING --------------------

	@Override
	public void render(@NotNull GuiGraphics g, int rawX, int rawY, float partialTick) {
		renderBackground(g, rawX, rawY, partialTick);
		g.pose().pushPose();
		g.pose().scale(scale, scale, 1.0F);
		int mouseX = (int)(rawX / scale), mouseY = (int)(rawY / scale);
		Font font = this.font;
		Win98.raised(g, leftPos, topPos, imageWidth, imageHeight);
		renderTitle(g, font, mouseX, mouseY);
		renderTabs(g, font);
		int panelY = topPos + 3 + TITLE_H + 2 + TAB_H;
		ComputerTab tab = tab();
		tab.render(g, font, mouseX, mouseY);
		renderStatusBar(g, font, panelY);
		for(W98Component component : tab.components)
			component.renderOverlay(g, font, mouseX, mouseY);
		tab.renderTooltip(g, font, mouseX, mouseY);
		if(boot != null && boot.running()) // Over the desktop, inside the window's frame, as its monitor.
			boot.render(g, font, leftPos + 3, topPos + 3, imageWidth - 6, imageHeight - 6);
		g.pose().popPose();
		desk.capture(g, leftPos * scale, topPos * scale, imageWidth * scale, imageHeight * scale);
	}

	@Override
	protected void renderBg(@NotNull GuiGraphics g, float partialTick, int mouseX, int mouseY) {}

	@Override
	protected void renderLabels(@NotNull GuiGraphics g, int mouseX, int mouseY) {}

	private int[] closeBox() {
		return new int[] { leftPos + imageWidth - 3 - 2 - 16, topPos + 5, 16, TITLE_H - 4 };
	}

	private void renderTitle(GuiGraphics g, Font font, int mouseX, int mouseY) {
		Win98.titleBar(g, font, leftPos + 3, topPos + 3, imageWidth - 6, TITLE_H, title.getString());
		int[] box = closeBox();
		boolean down = closeHeld && inside(box, mouseX, mouseY);
		if(down)
			Win98.pressed(g, box[0], box[1], box[2], box[3]);
		else
			Win98.raised(g, box[0], box[1], box[2], box[3]);
		int cx = box[0] + box[2] / 2 - 3 + (down ? 1 : 0), cy = box[1] + box[3] / 2 - 3 + (down ? 1 : 0);
		for(int i = 0; i < 6; i++) { // The close box's X, two pixels thick.
			g.fill(cx + i, cy + i, cx + i + 2, cy + i + 1, Win98.TEXT);
			g.fill(cx + 5 - i, cy + i, cx + 7 - i, cy + i + 1, Win98.TEXT);
		}
	}

	/** The tabs' rectangles, left to right. */
	private List<int[]> tabBoxes() {
		List<int[]> out = new ArrayList<>();
		int tx = leftPos + 5, ty = topPos + 3 + TITLE_H + 2;
		for(ComputerTab tab : tabs) {
			int tw = font.width(tab.title()) + 12;
			out.add(new int[] { tx, ty, tw, TAB_H });
			tx += tw;
		}
		return out;
	}

	private void renderTabs(GuiGraphics g, Font font) {
		int panelX = leftPos + 3, panelY = topPos + 3 + TITLE_H + 2 + TAB_H;
		int panelH = imageHeight - (panelY - topPos) - STATUS_H - 5;
		Win98.raised(g, panelX, panelY, imageWidth - 6, panelH);
		List<int[]> boxes = tabBoxes();
		for(int i = 0; i < boxes.size(); i++) {
			int[] box = boxes.get(i);
			boolean selected = i == desk.active;
			int bx = box[0] - (selected ? 2 : 0), by = box[1] - (selected ? 2 : 0), bw = box[2] + (selected ? 4 : 0), bh = box[3] + (selected ? 3 : 1);
			// A tab: rounded top corners, no bottom edge; the selected one stands taller and joins the panel.
			g.fill(bx + 1, by + 1, bx + bw - 1, by + bh, Win98.FACE);
			g.fill(bx + 2, by, bx + bw - 2, by + 1, Win98.HIGHLIGHT);
			g.fill(bx, by + 2, bx + 1, by + bh, Win98.HIGHLIGHT);
			g.fill(bx + 1, by + 1, bx + 2, by + 2, Win98.HIGHLIGHT);
			g.fill(bx + bw - 1, by + 2, bx + bw, by + bh, Win98.DARK);
			g.fill(bx + bw - 2, by + 1, bx + bw - 1, by + bh, Win98.SHADOW);
			if(selected)
				g.fill(bx + 1, by + bh - 1, bx + bw - 2, by + bh + 1, Win98.FACE);
			Win98.text(g, font, tabs.get(i).title(), box[0] + 6, box[1] + 3 - (selected ? 1 : 0), Win98.TEXT);
		}
	}

	private void renderStatusBar(GuiGraphics g, Font font, int panelY) {
		int sy = topPos + imageHeight - STATUS_H - 3, sx = leftPos + 3, sw = imageWidth - 6;
		CompoundTag status = desk.status;
		boolean radio = status.getBoolean("radio"), proximity = status.getBoolean("proximity");
		String rec = tr(radio || proximity ? "status.rec_on" : "status.rec_off", tr(radio ? "on" : "off"), tr(proximity ? "on" : "off"));
		String storage = status.contains("bytes") ? tr("status.storage", ModerationFormat.bytes(status.getLong("bytes")),
				String.format(java.util.Locale.ROOT, "%.1f", status.getDouble("cap")), status.getInt("days")) : tr("status.no_log");
		int recW = font.width(rec) + 14, storageW = font.width(storage) + 8;
		Win98.thinSunken(g, sx, sy, recW, STATUS_H);
		if(radio || proximity) // A red dot, as a recorder's REC lamp.
			g.fill(sx + 4, sy + 5, sx + 8, sy + 9, Win98.RED);
		Win98.text(g, font, rec, sx + 10, sy + 3, Win98.TEXT);
		Win98.thinSunken(g, sx + recW + 2, sy, storageW, STATUS_H);
		Win98.text(g, font, storage, sx + recW + 6, sy + 3, Win98.TEXT);
		int mx = sx + recW + storageW + 4;
		Win98.thinSunken(g, mx, sy, sw - (mx - sx), STATUS_H);
		if(!desk.message.isEmpty() && System.currentTimeMillis() - desk.messageMs < MESSAGE_MS)
			Win98.text(g, font, Win98.fit(font, desk.message, sw - (mx - sx) - 8), mx + 4, sy + 3, desk.messageIsError ? Win98.RED : Win98.TEXT);
	}

	/** Clips drawing to {@code x0, y0} - {@code x1, y1} in this screen's coordinates (scissor works in GUI pixels). */
	void scissor(GuiGraphics g, int x0, int y0, int x1, int y1) {
		g.enableScissor((int)Math.floor(x0 * scale), (int)Math.floor(y0 * scale), (int)Math.ceil(x1 * scale), (int)Math.ceil(y1 * scale));
	}

	int screenWidth() {
		return width;
	}

	int screenHeight() {
		return height;
	}

	// -------------------- INPUT --------------------

	private static boolean inside(int[] box, double mouseX, double mouseY) {
		return mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= box[1] && mouseY < box[1] + box[3];
	}

	private void closePopups() {
		for(W98Component component : tab().components)
			component.closePopup();
	}

	private void setFocus(@Nullable W98Component component) {
		if(focused != null)
			focused.focused = false;
		focused = component;
		if(component != null)
			component.focused = true;
	}

	@Override
	public boolean mouseClicked(double rawX, double rawY, int button) {
		if(boot != null && boot.blocksInput()) { // A click skips the boot.
			boot.skip();
			return true;
		}
		double mouseX = rawX / scale, mouseY = rawY / scale;
		ComputerTab tab = tab();
		for(W98Component component : tab.components) // An open dropdown's list lies over everything.
			if(component.overlayContains(mouseX, mouseY))
				return component.mouseClicked(mouseX, mouseY, button);
		boolean popupOpen = false;
		for(W98Component component : tab.components)
			popupOpen |= component instanceof W98Dropdown dropdown && dropdown.isOpen();
		if(popupOpen) {
			closePopups();
			return true;
		}
		if(inside(closeBox(), mouseX, mouseY)) {
			closeHeld = true;
			return true;
		}
		List<int[]> boxes = tabBoxes();
		for(int i = 0; i < boxes.size(); i++)
			if(inside(boxes.get(i), mouseX, mouseY)) {
				selectTab(i);
				return true;
			}
		for(W98Component component : tab.components)
			if(component.visible && component.contains(mouseX, mouseY)) {
				setFocus(component.takesFocus() ? component : null);
				if(component.mouseClicked(mouseX, mouseY, button))
					return true;
			}
		setFocus(null);
		return tab.mouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseReleased(double rawX, double rawY, int button) {
		double mouseX = rawX / scale, mouseY = rawY / scale;
		if(closeHeld) {
			closeHeld = false;
			if(inside(closeBox(), mouseX, mouseY))
				onClose();
			return true;
		}
		boolean used = false;
		for(W98Component component : tab().components)
			used |= component.mouseReleased(mouseX, mouseY, button);
		return used || tab().mouseReleased(mouseX, mouseY, button) || super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double rawX, double rawY, int button, double dragX, double dragY) {
		double mouseX = rawX / scale, mouseY = rawY / scale;
		for(W98Component component : tab().components)
			if(component.mouseDragged(mouseX, mouseY, button))
				return true;
		return tab().mouseDragged(mouseX, mouseY, button, dragX / scale, dragY / scale);
	}

	@Override
	public boolean mouseScrolled(double rawX, double rawY, double scrollX, double scrollY) {
		double mouseX = rawX / scale, mouseY = rawY / scale;
		for(W98Component component : tab().components)
			if(component.overlayContains(mouseX, mouseY))
				return component.mouseScrolled(mouseX, mouseY, scrollY);
		for(W98Component component : tab().components)
			if(component.visible && component.mouseScrolled(mouseX, mouseY, scrollY))
				return true;
		return tab().mouseScrolled(mouseX, mouseY, scrollY);
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if(boot != null && boot.blocksInput() && key != GLFW.GLFW_KEY_ESCAPE) { // Any key but Escape skips the boot.
			boot.skip();
			return true;
		}
		if(key == GLFW.GLFW_KEY_ESCAPE && focused != null) { // Escape leaves the text box first.
			setFocus(null);
			return true;
		}
		if(key == GLFW.GLFW_KEY_TAB && focused != null) { // Tab to the next text box on the page.
			List<W98Component> boxes = tab().components.stream().filter(c -> c.visible && c.takesFocus()).toList();
			int at = boxes.indexOf(focused);
			setFocus(boxes.get((at + (hasShiftDown() ? boxes.size() - 1 : 1)) % boxes.size()));
			return true;
		}
		if(focused != null && focused.keyPressed(key, scanCode, modifiers))
			return true;
		return super.keyPressed(key, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		if(boot != null && boot.blocksInput())
			return true; // The key that skipped the boot types nothing.
		if(focused != null && focused.charTyped(c, modifiers))
			return true;
		return super.charTyped(c, modifiers);
	}
}
