package com.arrl.radiocraft.client.screens.moderation;

import com.arrl.radiocraft.common.init.RadiocraftSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.HashSet;
import java.util.Set;

/**
 * The moderation computer starting up, drawn in its window before the desktop: the BIOS power-on self test (a memory
 * count, drives found), then the "Radio Moderation 98" splash with its sliding progress bar, and a startup chime as
 * the desktop comes up and fades in. The first time a computer is opened in a session it boots in full; after that only
 * the splash (and not even that if its screen was left open, see {@link ModerationDesk}). Any key or click skips it.
 * The only sounds are the memory count ticking (the H19's keys pitched up) and the chime, a bell and chime arpeggio from
 * note blocks.
 */
final class BootSequence {

	/** Computers already booted this session: they show only the splash. */
	private static final Set<BlockPos> BOOTED = new HashSet<>();

	// The full boot's timeline, in ms.
	private static final long MEMORY_END = 1100L, POST_END = 3000L, SPLASH_END = 5600L, FADE = 450L;
	// The short one: the splash only.
	private static final long SHORT_SPLASH = 1400L;
	private static final long MEMORY_KB = 65536L;

	private static final int BLACK = 0xFF000000, GREY = 0xFFAAAAAA, WHITE = 0xFFFFFFFF, AMBER = 0xFFFFB000;

	private final boolean full;
	private final long start = System.currentTimeMillis();
	private long skippedAt = -1L;
	private long nextClick = 0L;
	private int chimeNotes = 0;

	BootSequence(BlockPos pos) {
		full = BOOTED.add(pos.immutable());
	}

	/** Boots the computer at {@code pos} in full, from the BIOS (a reboot). */
	static BootSequence cold(BlockPos pos) {
		BOOTED.remove(pos);
		return new BootSequence(pos);
	}

	private long elapsed() {
		return System.currentTimeMillis() - start;
	}

	/** When the desktop shows (the splash ends). */
	private long desktopAt() {
		return skippedAt >= 0L ? skippedAt : full ? SPLASH_END : SHORT_SPLASH;
	}

	/** True while the boot still covers the desktop (its fade included). */
	boolean running() {
		return elapsed() < desktopAt() + FADE;
	}

	/** True while it takes keys and clicks (until the desktop shows). */
	boolean blocksInput() {
		return elapsed() < desktopAt();
	}

	/** A key or click: straight to the desktop and its chime. */
	void skip() {
		if(skippedAt < 0L && elapsed() < desktopAt())
			skippedAt = elapsed();
	}

	/** Draws the boot over the window at ({@code x}, {@code y}), {@code w} by {@code h}, and plays what is due. */
	void render(GuiGraphics g, Font font, int x, int y, int w, int h) {
		long t = elapsed();
		sounds(t);
		long desktop = desktopAt();
		if(t >= desktop) { // The desktop has been drawn underneath: fade the black away.
			int alpha = (int)(255 * Math.max(0.0D, 1.0D - (t - desktop) / (double)FADE));
			if(alpha > 0)
				g.fill(x, y, x + w, y + h, alpha << 24);
			return;
		}
		g.fill(x, y, x + w, y + h, BLACK);
		if(full && t < POST_END)
			post(g, font, x, y, w, h, t);
		else
			splash(g, font, x, y, w, h, t - (full ? POST_END : 0L));
	}

	// -------------------- POWER-ON SELF TEST --------------------

	private void post(GuiGraphics g, Font font, int x, int y, int w, int h, long t) {
		int line = 0;
		int left = x + 8, top = y + 8, step = font.lineHeight + 2;
		// The energy star in the corner, as the BIOS of the day showed it.
		g.drawString(font, "★ ENERGY", x + w - 8 - font.width("★ ENERGY"), top, AMBER, false);
		g.drawString(font, "RadioCraft Modular BIOS v4.51PG", left, top + step * line++, WHITE, false);
		g.drawString(font, "Copyright (C) 1984-98, ARRL Award Software, Inc.", left, top + step * line++, GREY, false);
		line++;
		g.drawString(font, "Pentium-II CPU at 400MHz", left, top + step * line++, GREY, false);
		long kb = Math.min(MEMORY_KB, MEMORY_KB * t / MEMORY_END) / 64 * 64;
		g.drawString(font, "Memory Test :  " + kb + "K" + (t >= MEMORY_END ? " OK" : ""), left, top + step * line++, GREY, false);
		line++;
		String[][] drives = {
				{ "Detecting IDE Primary Master  ...", " RC-MODLOG 5.1GB" },
				{ "Detecting IDE Primary Slave   ...", " None" },
				{ "Detecting IDE Secondary Master...", " CD-ROM 24X" },
		};
		for(int i = 0; i < drives.length; i++) {
			long at = MEMORY_END + 250L + i * 350L;
			if(t < at)
				break;
			g.drawString(font, drives[i][0] + (t >= at + 200L ? drives[i][1] : ""), left, top + step * line++, GREY, false);
		}
		if(t >= 2350L)
			g.drawString(font, "Verifying DMI Pool Data ........ Update Success", left, top + step * (line + 1), GREY, false);
		if(t >= 2700L)
			g.drawString(font, "Starting Radio Moderation 98...", left, top + step * (line + 3), WHITE, false);
		g.drawString(font, "Press DEL to enter SETUP", left, y + h - 8 - font.lineHeight, GREY, false);
		if((t / 400L) % 2L == 0L && t >= 2700L) // The cursor.
			g.fill(left + font.width("Starting Radio Moderation 98...") + 2, top + step * (line + 3) + font.lineHeight - 2,
					left + font.width("Starting Radio Moderation 98...") + 8, top + step * (line + 3) + font.lineHeight, WHITE);
	}

	// -------------------- SPLASH --------------------

	private void splash(GuiGraphics g, Font font, int x, int y, int w, int h, long t) {
		// A sky of navy to blue, as the splash's clouds would be at this size.
		int bands = 16;
		for(int i = 0; i < bands; i++) {
			int y0 = y + h * i / bands, y1 = y + h * (i + 1) / bands;
			int blue = 0x40 + 0x90 * i / bands, green = 0x20 + 0x50 * i / bands;
			g.fill(x, y0, x + w, y1, 0xFF000000 | (0x10 << 16) | (green << 8) | blue);
		}
		// The flag: four panes, waving a little; the green one is RadioCraft violet, so it is its own flag.
		int size = Math.max(10, Math.min(w, h) / 9);
		int cx = x + w / 2 - size * 3, cy = y + h / 2 - size;
		int[] colours = { 0xFFF25022, 0xFF9B5DE5, 0xFF00A4EF, 0xFFFFB900 };
		for(int i = 0; i < 4; i++) {
			int col = i % 2, row = i / 2;
			int wave = (int)Math.round(Math.sin(t / 220.0D + col * 0.9D) * size * 0.12D);
			int px = cx + col * (size + 2), py = cy + row * (size + 2) + wave;
			g.fill(px, py, px + size, py + size, colours[i]);
		}
		// "Radio Moderation" with an orange 98, twice the size.
		g.pose().pushPose();
		int tx = cx + size * 2 + 10, ty = cy + size / 2;
		g.pose().translate(tx, ty, 0.0F);
		g.pose().scale(2.0F, 2.0F, 1.0F);
		g.drawString(font, "Radio", 0, 0, WHITE, true);
		g.drawString(font, "Moderation", 0, font.lineHeight + 1, WHITE, true);
		g.drawString(font, "98", font.width("Moderation") + 2, font.lineHeight - 3, 0xFFFF8C1A, true);
		g.pose().popPose();
		g.drawString(font, "RadioCraft", tx, ty - font.lineHeight - 2, 0xFFCFE0FF, false);
		// The progress strip: a run of blues sliding along the bottom, round and round.
		int barY = y + h - 10, barH = 6;
		g.fill(x, barY, x + w, barY + barH, 0xFF000020);
		int runW = Math.max(40, w / 3);
		int offset = (int)((t / 6L) % (w + runW)) - runW;
		for(int i = 0; i < runW; i++) {
			double shade = Math.sin(Math.PI * i / runW);
			int blue = (int)(0x50 + 0xAF * shade), green = (int)(0x30 + 0x70 * shade);
			int px = x + offset + i;
			if(px >= x && px < x + w)
				g.fill(px, barY, px + 1, barY + barH, 0xFF000000 | (green << 8) | blue);
		}
	}

	// -------------------- SOUNDS --------------------

	private void sounds(long t) {
		long desktop = desktopAt();
		if(t < desktop && full && t < MEMORY_END && t >= nextClick) { // The memory count ticking.
			play(RadiocraftSoundEvents.H19_TYPE.get(), 1.8F, 0.08F);
			nextClick = t + 70L;
		}
		// The chime as the desktop comes up: a rising bell arpeggio over a soft chime.
		float[] pitches = { 0.749F, 0.944F, 1.122F, 1.498F };
		long[] at = { 0L, 140L, 280L, 460L };
		while(chimeNotes < pitches.length && t >= desktop + at[chimeNotes]) {
			play(SoundEvents.NOTE_BLOCK_BELL.value(), pitches[chimeNotes], 0.35F);
			if(chimeNotes == 0 || chimeNotes == pitches.length - 1)
				play(SoundEvents.NOTE_BLOCK_CHIME.value(), pitches[chimeNotes], 0.25F);
			chimeNotes++;
		}
	}

	private static void play(SoundEvent sound, float pitch, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}
}
