package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.init.RadiocraftSoundEvents;
import com.arrl.radiocraft.common.network.serverbound.SStationPacket;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What one moderation computer's screen is showing, kept when the screen closes: the tabs as they were (filters,
 * selections, scroll, the map's view) and what the server last said. Opened again within {@link #KEEP_MS} it carries on
 * where it was left, without booting; after that it starts afresh. The tabs talk to it, and it to whichever
 * {@link ModerationComputerScreen} has it open.
 * <p>
 * It also keeps a picture of its window for the computer's monitor in the world ({@link #monitor}): copied from the
 * screen every few frames while it is open, so the monitor shows what was on it when it was left.
 */
public final class ModerationDesk {

	/** How long a closed computer's screen is kept, ms. */
	static final long KEEP_MS = 10L * 60L * 1000L;
	/** The monitor picture's size, pixels; about the window's shape. */
	public static final int MONITOR_W = 512, MONITOR_H = 336;

	private static final Map<BlockPos, ModerationDesk> DESKS = new HashMap<>();
	private static int monitors = 0;

	final BlockPos pos;
	private final ClientLevel level;
	final List<ComputerTab> tabs = new ArrayList<>();
	final ArchiveTab archive;
	int active = 0;
	@Nullable
	private ModerationComputerScreen screen;
	private long closedAt = -1L;
	private long lastBellMs = 0L;

	// What the server last said, shared by the tabs.
	CompoundTag status = new CompoundTag();
	CompoundTag access = new CompoundTag();
	CompoundTag listening = new CompoundTag();
	List<String> players = new ArrayList<>();
	List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
	long playingId = 0L, playPosition = 0L, playLength = 0L;
	boolean playing = false;
	String message = "";
	boolean messageIsError = false;
	long messageMs = 0L;

	@Nullable
	private TextureTarget monitor;
	@Nullable
	private ResourceLocation monitorId;
	private int frames = 0;

	private ModerationDesk(BlockPos pos, ClientLevel level) {
		this.pos = pos;
		this.level = level;
		tabs.add(new LiveTab(this, false));
		archive = new ArchiveTab(this, false);
		tabs.add(archive);
		tabs.add(new MapTab(this));
		tabs.add(new LinksTab(this));
		tabs.add(new TapesTab(this));
		tabs.add(new ProximityTab(this));
		tabs.add(new AccessTab(this));
	}

	/**
	 * The desk for the computer at {@code pos}, now shown by {@code screen}: the one left there if it was closed within
	 * {@link #KEEP_MS}, else a new one.
	 */
	static ModerationDesk open(BlockPos pos, ModerationComputerScreen screen) {
		forget();
		ClientLevel level = Minecraft.getInstance().level;
		ModerationDesk desk = DESKS.get(pos);
		if(desk == null || desk.screen != null) {
			if(desk != null)
				desk.release();
			desk = new ModerationDesk(pos.immutable(), level);
			DESKS.put(desk.pos, desk);
		}
		desk.screen = screen;
		return desk;
	}

	/**
	 * A new desk for the computer at {@code pos} in place of {@code old} (a reboot), shown by the same screen; the
	 * monitor's picture goes with it.
	 */
	static ModerationDesk replace(ModerationDesk old, ModerationComputerScreen screen) {
		old.screen = null;
		for(ComputerTab tab : old.tabs)
			tab.closed();
		ModerationDesk desk = new ModerationDesk(old.pos, old.level);
		desk.monitor = old.monitor;
		desk.monitorId = old.monitorId;
		desk.screen = screen;
		DESKS.put(desk.pos, desk);
		return desk;
	}

	/** True if this desk was in use before this opening (its screen carries on, no boot). */
	boolean resumed() {
		return closedAt >= 0L;
	}

	/** Its screen closed: playback stops with it (the server stops it), the rest is kept. */
	void closed() {
		screen = null;
		closedAt = System.currentTimeMillis();
		playing = false;
		playPosition = 0L;
		for(ComputerTab tab : tabs)
			tab.closed();
	}

	/** Drops desks closed too long ago, or left in another world. */
	private static void forget() {
		long now = System.currentTimeMillis();
		ClientLevel level = Minecraft.getInstance().level;
		for(Iterator<ModerationDesk> it = DESKS.values().iterator(); it.hasNext(); ) {
			ModerationDesk desk = it.next();
			if(desk.screen == null && (now - desk.closedAt > KEEP_MS || desk.level != level)) {
				desk.release();
				it.remove();
			}
		}
	}

	private void release() {
		if(monitorId != null)
			Minecraft.getInstance().getTextureManager().release(monitorId);
		if(monitor != null)
			monitor.destroyBuffers();
		monitor = null;
		monitorId = null;
	}

	// -------------------- THE MONITOR --------------------

	/**
	 * The picture of the screen left on the computer at {@code pos}, for its monitor in the world; null if there is none
	 * (never opened here, or closed long ago: the monitor shows its plain desktop). Render thread.
	 */
	@Nullable
	public static ResourceLocation monitor(BlockPos pos) {
		forget();
		ModerationDesk desk = DESKS.get(pos);
		return desk == null ? null : desk.monitorId;
	}

	/**
	 * Copies the window, at {@code x}, {@code y}, {@code w} by {@code h} in GUI pixels, from the frame being drawn into
	 * the monitor's picture, every few frames. Called at the end of the screen's drawing.
	 */
	void capture(GuiGraphics g, double x, double y, double w, double h) {
		if(frames++ % 4 != 0)
			return;
		g.flush();
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft.getMainRenderTarget();
		if(monitor == null) {
			monitor = new TextureTarget(MONITOR_W, MONITOR_H, false, Minecraft.ON_OSX);
			monitor.setFilterMode(GL11.GL_LINEAR);
			monitorId = Radiocraft.id("moderation_monitor/" + monitors++);
			minecraft.getTextureManager().register(monitorId, new MonitorTexture(monitor.getColorTextureId()));
		}
		double scale = minecraft.getWindow().getGuiScale();
		int x0 = (int)Math.round(x * scale), x1 = (int)Math.round((x + w) * scale);
		int y0 = (int)Math.round(y * scale), y1 = (int)Math.round((y + h) * scale);
		// GL counts rows from the bottom.
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, monitor.frameBufferId);
		GlStateManager._glBlitFrameBuffer(x0, main.height - y1, x1, main.height - y0, 0, 0, MONITOR_W, MONITOR_H,
				GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
		// The screen's own alpha is whatever its blending left; the picture is opaque.
		monitor.bindWrite(false);
		RenderSystem.colorMask(false, false, false, true);
		RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 1.0F);
		RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
		RenderSystem.colorMask(true, true, true, true);
		main.bindWrite(true);
	}

	/** The picture's colour texture, owned by its render target (which frees it), registered so a render type can use it. */
	private static final class MonitorTexture extends AbstractTexture {
		MonitorTexture(int id) {
			this.id = id;
		}

		@Override
		public void load(ResourceManager resourceManager) {}

		@Override
		public void releaseId() {
			id = -1;
		}

		@Override
		public void close() {
			id = -1;
		}
	}

	// -------------------- FOR THE TABS --------------------

	/** Asks the server: {@code action} with {@code data} (see {@code ComputerRequests}). */
	void send(String action, CompoundTag data) {
		SStationPacket.send(pos, action, data);
	}

	void send(String action) {
		send(action, new CompoundTag());
	}

	/** Who this player listens to live: "sends", "hears", "voice" or empty. */
	String listeningMode() {
		return listening.getString("mode");
	}

	void showMessage(String text, boolean error) {
		message = text;
		messageIsError = error;
		messageMs = System.currentTimeMillis();
	}

	/** Starts the computer afresh (Access, Reboot). */
	void reboot() {
		if(screen != null)
			screen.reboot();
	}

	/** Shows the Archive tab filtered to the radio at {@code pos} (from the map). */
	void showRadioInArchive(String dimension, int x, int y, int z) {
		if(screen != null)
			screen.selectTab(tabs.indexOf(archive));
		archive.filterRadio(dimension, x, y, z);
	}

	/** A key of the keyboard, quietly. */
	void keyClick() {
		play(RadiocraftSoundEvents.H19_TYPE.get(), 0.95F + (float)Math.random() * 0.1F, 0.25F);
	}

	/** The bell, at most every few seconds. */
	void bell() {
		long now = System.currentTimeMillis();
		if(now - lastBellMs < 4000L)
			return;
		lastBellMs = now;
		play(RadiocraftSoundEvents.H19_BELL.get(), 1.0F, 0.3F);
	}

	private static void play(SoundEvent sound, float pitch, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	/** Clips drawing to {@code x0, y0} - {@code x1, y1} in the screen's coordinates. */
	void scissor(GuiGraphics g, int x0, int y0, int x1, int y1) {
		if(screen != null)
			screen.scissor(g, x0, y0, x1, y1);
	}

	int screenWidth() {
		return screen == null ? 0 : screen.screenWidth();
	}

	int screenHeight() {
		return screen == null ? 0 : screen.screenHeight();
	}
}
