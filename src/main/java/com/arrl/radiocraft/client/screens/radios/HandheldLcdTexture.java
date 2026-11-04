package com.arrl.radiocraft.client.screens.radios;

import com.arrl.radiocraft.Radiocraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The LCD of a handheld drawn into a small texture, for a radio shown in the world (in a hand, on a belt, in a desk
 * charger): the same main display as its screen. One texture per radio (keyed by the charger's position, or the item
 * stack itself), repainted a few times a second; the least recently drawn are let go once there are more than
 * {@link #MAX_TEXTURES}.
 */
public final class HandheldLcdTexture {

	private HandheldLcdTexture() {}

	public static final int WIDTH = HandheldLcd.WIDTH;
	public static final int HEIGHT = HandheldLcd.HEIGHT;
	private static final int MAX_TEXTURES = 32;
	private static int created = 0; // Names each texture.
	private static final long REPAINT_MILLIS = 100L;

	private static final class Entry {
		final DynamicTexture texture;
		final ResourceLocation id;
		long paintedAt = 0L;

		Entry(DynamicTexture texture, ResourceLocation id) {
			this.texture = texture;
			this.id = id;
		}
	}

	private static final Map<Object, Entry> TEXTURES = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Object, Entry> eldest) {
			if(size() <= MAX_TEXTURES)
				return false;
			Minecraft.getInstance().getTextureManager().release(eldest.getValue().id);
			return true;
		}
	};

	/**
	 * The texture showing {@code stack}'s main display, with {@code status} boxed bottom left and the backlight on if
	 * {@code backlit}, for the radio known by {@code key} (a charger's {@link BlockPos}, or the stack). Render thread only.
	 */
	public static ResourceLocation paint(Object key, ItemStack stack, String status, boolean backlit) {
		Entry entry = TEXTURES.get(key);
		if(entry == null) {
			DynamicTexture texture = new DynamicTexture(WIDTH, HEIGHT, true);
			ResourceLocation id = Radiocraft.id("handheld_lcd/" + Integer.toHexString(++created));
			Minecraft.getInstance().getTextureManager().register(id, texture);
			entry = new Entry(texture, id);
			TEXTURES.put(key instanceof BlockPos pos ? pos.immutable() : key, entry);
		}
		long now = System.currentTimeMillis();
		NativeImage image = entry.texture.getPixels();
		if(image != null && now - entry.paintedAt >= REPAINT_MILLIS) {
			entry.paintedAt = now;
			HandheldLcd lcd = new HandheldLcd(canvas(image));
			lcd.clear(backlit);
			lcd.main(stack, status);
			entry.texture.upload();
		}
		return entry.id;
	}

	/** Draws into {@code image}, blending see-through colours over what is there. */
	private static HandheldLcd.Canvas canvas(NativeImage image) {
		return (x0, y0, x1, y1, argb) -> {
			int alpha = argb >>> 24;
			for(int y = Math.max(0, y0); y < Math.min(HEIGHT, y1); y++)
				for(int x = Math.max(0, x0); x < Math.min(WIDTH, x1); x++)
					image.setPixelRGBA(x, y, alpha == 255 ? abgr(argb) : abgr(blend(argb(image.getPixelRGBA(x, y)), argb, alpha / 255.0F)));
		};
	}

	private static int blend(int under, int over, float share) {
		int r = Math.round(((under >> 16) & 0xFF) * (1 - share) + ((over >> 16) & 0xFF) * share);
		int g = Math.round(((under >> 8) & 0xFF) * (1 - share) + ((over >> 8) & 0xFF) * share);
		int b = Math.round((under & 0xFF) * (1 - share) + (over & 0xFF) * share);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** NativeImage keeps pixels as ABGR; the LCD's colours are ARGB. */
	private static int abgr(int argb) {
		return (argb & 0xFF00FF00) | (argb >> 16 & 0xFF) | (argb & 0xFF) << 16;
	}

	private static int argb(int abgr) {
		return abgr(abgr); // Swapping red and blue is its own inverse.
	}
}
