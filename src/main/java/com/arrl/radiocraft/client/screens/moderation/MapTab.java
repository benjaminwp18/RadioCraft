package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.client.screens.AprsMapTexture;
import com.arrl.radiocraft.common.moderation.ModerationFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Map: the radios that sent or heard something in the chosen world and time range, from above. In the world the
 * player is in, the ground the client has loaded is painted under them as the H19's APRS map paints it
 * ({@link AprsMapTexture}); elsewhere a plain grid. Red squares sent, blue ones only heard. Hover one for its kind, last
 * talker and frequency; click to pick it and "Show in Archive" lists its transmissions. Drag to pan, wheel to zoom.
 */
class MapTab extends ComputerTab {

	private static final int SITE = 5;

	private final W98Dropdown dimension, range;
	private final W98Button refresh, fit, showInArchive;
	private List<CompoundTag> sites = List.of();
	private ModerationLayout.View view = new ModerationLayout.View(0.0D, 0.0D, 1.0D);
	/** Where the terrain was last painted; drawn shifted from there while dragging, repainted on release. */
	private ModerationLayout.View painted = null;
	private AprsMapTexture texture;
	private int textureW, textureH;
	private int mapX, mapY, mapW, mapH;
	private boolean dragging = false;
	private CompoundTag selected = null;
	private boolean fitOnNext = true;
	private String shownDimension = "";

	MapTab(ModerationDesk screen) {
		super(screen);
		dimension = add(new W98Dropdown(() -> screen.dimensions).onChange(i -> {
			fitOnNext = true;
			ask();
		}));
		range = add(new W98Dropdown(ComputerTab::rangeLabels).onChange(i -> ask()));
		range.select(ModerationFormat.Range.LAST_7_DAYS.ordinal());
		refresh = add(new W98Button(() -> ModerationComputerScreen.tr("map.refresh"), this::ask));
		fit = add(new W98Button(() -> ModerationComputerScreen.tr("map.fit"), this::fitView));
		showInArchive = add(new W98Button(() -> ModerationComputerScreen.tr("map.show_in_archive"), () -> {
			if(selected != null) {
				BlockPos pos = BlockPos.of(selected.getLong("pos"));
				screen.showRadioInArchive(selected.getString("dim"), pos.getX(), pos.getY(), pos.getZ());
			}
		}).enabledWhen(() -> selected != null));
	}

	@Override
	String title() {
		return ModerationComputerScreen.tr("tab.map");
	}

	@Override
	void place() {
		int cx = label(() -> ModerationComputerScreen.tr("archive.world"), x, y);
		dimension.bounds(cx, y, 110, ROW);
		cx = label(() -> ModerationComputerScreen.tr("archive.when"), cx + 114, y);
		range.bounds(cx, y, 84, ROW);
		cx += 88;
		int refreshW = font().width(ModerationComputerScreen.tr("map.refresh")) + 14, fitW = font().width(ModerationComputerScreen.tr("map.fit")) + 14;
		refresh.bounds(cx, y, refreshW, ROW);
		fit.bounds(cx + refreshW + GAP, y, fitW, ROW);
		int showW = font().width(ModerationComputerScreen.tr("map.show_in_archive")) + 14;
		showInArchive.bounds(x + w - showW, y, showW, ROW);
		mapX = x;
		mapY = y + ROW + GAP;
		mapW = w;
		mapH = h - ROW - GAP;
		painted = null;
	}

	@Override
	void shown() {
		Minecraft minecraft = Minecraft.getInstance();
		if(dimension.selected() == 0 && minecraft.level != null) { // Start in the world the player is in.
			int index = screen.dimensions.indexOf(minecraft.level.dimension().location().toString());
			if(index >= 0)
				dimension.select(index);
		}
		ask();
	}

	private String chosenDimension() {
		return screen.dimensions.isEmpty() ? "" : screen.dimensions.get(dimension.selected());
	}

	private void ask() {
		CompoundTag data = new CompoundTag();
		data.putString("dim", chosenDimension());
		data.putInt("range", range.selected());
		screen.send("radios", data);
	}

	@Override
	void receive(String kind, CompoundTag data) {
		if(!kind.equals("radios") || !data.getString("dim").equals(chosenDimension()))
			return;
		sites = rows(data, "sites");
		if(selected != null) { // Keep the pick if that radio is still there.
			long pos = selected.getLong("pos");
			selected = sites.stream().filter(site -> site.getLong("pos") == pos).findFirst().orElse(null);
		}
		if(fitOnNext || !shownDimension.equals(chosenDimension())) {
			fitOnNext = false;
			shownDimension = chosenDimension();
			fitView();
		}
	}

	private void fitView() {
		int[] xs = new int[sites.size()], zs = new int[sites.size()];
		for(int i = 0; i < sites.size(); i++) {
			BlockPos pos = BlockPos.of(sites.get(i).getLong("pos"));
			xs[i] = pos.getX();
			zs[i] = pos.getZ();
		}
		if(sites.isEmpty() && Minecraft.getInstance().player != null) { // Nothing logged: show where the player stands.
			xs = new int[] { Minecraft.getInstance().player.getBlockX() };
			zs = new int[] { Minecraft.getInstance().player.getBlockZ() };
		}
		view = ModerationLayout.fit(xs, zs, mapW - 4, mapH - 4);
		painted = null;
	}

	@Override
	void closed() {
		if(texture != null) {
			texture.close();
			texture = null;
		}
	}

	// -------------------- DRAWING --------------------

	@Override
	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		super.render(g, font, mouseX, mouseY);
		Win98.sunken(g, mapX, mapY, mapW, mapH, 0xFF1E2224);
		int ix = mapX + 2, iy = mapY + 2, iw = mapW - 4, ih = mapH - 4;
		screen.scissor(g, ix, iy, ix + iw, iy + ih);
		Minecraft minecraft = Minecraft.getInstance();
		if(minecraft.level != null && minecraft.level.dimension().location().toString().equals(chosenDimension()))
			terrain(g, minecraft, ix, iy, iw, ih);
		grid(g, ix, iy, iw, ih);
		CompoundTag hover = siteAt(mouseX, mouseY);
		for(CompoundTag site : sites) {
			BlockPos pos = BlockPos.of(site.getLong("pos"));
			int sx = (int)Math.round(view.screenX(pos.getX() + 0.5D, ix, iw)), sy = (int)Math.round(view.screenY(pos.getZ() + 0.5D, iy, ih));
			int colour = site.getInt("sent") > 0 ? 0xFFE03030 : 0xFF3070E0;
			boolean picked = site == selected || site == hover;
			g.fill(sx - SITE / 2 - 1, sy - SITE / 2 - 1, sx + SITE / 2 + 2, sy + SITE / 2 + 2, picked ? 0xFFFFFFFF : 0xFF000000);
			g.fill(sx - SITE / 2, sy - SITE / 2, sx + SITE / 2 + 1, sy + SITE / 2 + 1, colour);
		}
		if(minecraft.player != null && minecraft.level != null && minecraft.level.dimension().location().toString().equals(chosenDimension())) {
			int px = (int)Math.round(view.screenX(minecraft.player.getX(), ix, iw)), py = (int)Math.round(view.screenY(minecraft.player.getZ(), iy, ih));
			g.fill(px - 1, py - 3, px + 2, py + 4, 0xFFFFFFFF); // You are here: a small white cross.
			g.fill(px - 3, py - 1, px + 4, py + 2, 0xFFFFFFFF);
		}
		g.disableScissor();
		String scale = ModerationComputerScreen.tr("map.scale", String.format(java.util.Locale.ROOT, "%.1f", view.blocksPerPixel()), sites.size());
		Win98.text(g, font, scale, ix + 3, iy + ih - 10, 0xFFE0E0E0);
		if(sites.isEmpty())
			Win98.text(g, font, ModerationComputerScreen.tr("map.empty"), ix + 6, iy + 6, 0xFFE0E0E0);
	}

	/** The ground, painted when the view settles; shifted with the view while it is dragged. */
	private void terrain(GuiGraphics g, Minecraft minecraft, int ix, int iy, int iw, int ih) {
		if(texture == null || textureW != iw || textureH != ih) {
			closed();
			texture = new AprsMapTexture(iw, ih);
			textureW = iw;
			textureH = ih;
			painted = null;
		}
		if(painted == null || (!dragging && (painted.blocksPerPixel() != view.blocksPerPixel() || painted.centerX() != view.centerX() || painted.centerZ() != view.centerZ())))
			painted = view;
		if(!dragging && minecraft.level != null)
			texture.update(minecraft.level, BlockPos.containing(painted.centerX(), 0.0D, painted.centerZ()), iw / 2.0D, ih / 2.0D, painted.blocksPerPixel());
		int dx = (int)Math.round((painted.centerX() - view.centerX()) / view.blocksPerPixel()), dz = (int)Math.round((painted.centerZ() - view.centerZ()) / view.blocksPerPixel());
		if(painted.blocksPerPixel() == view.blocksPerPixel())
			g.blit(AprsMapTexture.LOCATION, ix + dx, iy + dz, 0, 0.0F, 0.0F, iw, ih, iw, ih);
	}

	/** Grid lines every so many blocks, a power of two that leaves them 30 to 60 pixels apart. */
	private void grid(GuiGraphics g, int ix, int iy, int iw, int ih) {
		double step = 16.0D;
		while(step / view.blocksPerPixel() < 30.0D)
			step *= 2.0D;
		while(step / view.blocksPerPixel() > 60.0D && step > 1.0D)
			step /= 2.0D;
		double leftBlock = view.centerX() - iw / 2.0D * view.blocksPerPixel(), topBlock = view.centerZ() - ih / 2.0D * view.blocksPerPixel();
		for(double bx = Math.ceil(leftBlock / step) * step; bx < leftBlock + iw * view.blocksPerPixel(); bx += step) {
			int sx = (int)Math.round(view.screenX(bx, ix, iw));
			g.fill(sx, iy, sx + 1, iy + ih, 0x30FFFFFF);
		}
		for(double bz = Math.ceil(topBlock / step) * step; bz < topBlock + ih * view.blocksPerPixel(); bz += step) {
			int sy = (int)Math.round(view.screenY(bz, iy, ih));
			g.fill(ix, sy, ix + iw, sy + 1, 0x30FFFFFF);
		}
	}

	private CompoundTag siteAt(double mouseX, double mouseY) {
		int ix = mapX + 2, iy = mapY + 2, iw = mapW - 4, ih = mapH - 4;
		if(mouseX < ix || mouseX >= ix + iw || mouseY < iy || mouseY >= iy + ih)
			return null;
		CompoundTag best = null;
		double bestDistance = 6.0D * 6.0D;
		for(CompoundTag site : sites) {
			BlockPos pos = BlockPos.of(site.getLong("pos"));
			double dx = view.screenX(pos.getX() + 0.5D, ix, iw) - mouseX, dy = view.screenY(pos.getZ() + 0.5D, iy, ih) - mouseY;
			if(dx * dx + dy * dy < bestDistance) {
				bestDistance = dx * dx + dy * dy;
				best = site;
			}
		}
		return best;
	}

	@Override
	void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
		CompoundTag site = siteAt(mouseX, mouseY);
		if(site == null || dragging)
			return;
		List<String> lines = new ArrayList<>();
		BlockPos pos = BlockPos.of(site.getLong("pos"));
		lines.add(ModerationComputerScreen.radioName(site.getString("radio")) + "  " + ModerationFormat.pos(pos.getX(), pos.getY(), pos.getZ()));
		if(!site.getString("talker").isEmpty())
			lines.add(ModerationComputerScreen.tr("map.last_talker", site.getString("talker")));
		if(site.contains("hz"))
			lines.add(ModerationComputerScreen.tr("map.frequency", ModerationFormat.mhz(site.getFloat("hz"))));
		lines.add(ModerationComputerScreen.tr("map.counts", site.getInt("sent"), site.getInt("heard")));
		if(site.getLong("last") > 0L)
			lines.add(ModerationComputerScreen.tr("map.last", ModerationFormat.time(site.getLong("last"), ZoneId.systemDefault())));
		Win98.tooltip(g, font, lines, mouseX, mouseY, screen.screenWidth(), screen.screenHeight());
	}

	// -------------------- MOUSE --------------------

	private boolean onMap(double mouseX, double mouseY) {
		return mouseX >= mapX + 2 && mouseX < mapX + mapW - 2 && mouseY >= mapY + 2 && mouseY < mapY + mapH - 2;
	}

	@Override
	boolean mouseClicked(double mouseX, double mouseY, int button) {
		if(!onMap(mouseX, mouseY))
			return false;
		CompoundTag site = siteAt(mouseX, mouseY);
		if(site != null)
			selected = site;
		dragging = site == null;
		return true;
	}

	@Override
	boolean mouseReleased(double mouseX, double mouseY, int button) {
		if(!dragging)
			return false;
		dragging = false; // The terrain is repainted where the view is now.
		return true;
	}

	@Override
	boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if(!dragging)
			return false;
		view = view.panned(dragX, dragY);
		return true;
	}

	@Override
	boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		if(!onMap(mouseX, mouseY))
			return false;
		view = view.zoomed(amount > 0 ? 0.5D : 2.0D);
		return true;
	}
}
