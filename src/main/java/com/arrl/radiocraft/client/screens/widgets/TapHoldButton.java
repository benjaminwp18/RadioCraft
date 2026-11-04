package com.arrl.radiocraft.client.screens.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.NotNull;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * A key that does one thing when tapped and another when held down (the handheld's MW key: tap to switch VFO and
 * memory, hold to store). Like {@link HoverableImageButton} it draws nothing at rest (the key is in the background),
 * its hover sprite under the mouse and its pressed sprite while down. The hold fires as soon as it has been held long
 * enough, not on release; a release before that is a tap.
 */
public class TapHoldButton extends AbstractWidget {

	private final ResourceLocation texture;
	private final int uHover, vHover, uPressed, vPressed, textureWidth, textureHeight;
	private final long holdMillis;
	private final Runnable onTap;
	private final Runnable onHold;

	private long pressedAt = -1L; // -1 while up.
	private boolean held = false;

	public TapHoldButton(int x, int y, int width, int height, int uHover, int vHover, int uPressed, int vPressed, ResourceLocation texture,
						 int textureWidth, int textureHeight, long holdMillis, Runnable onTap, Runnable onHold) {
		super(x, y, width, height, CommonComponents.EMPTY);
		this.texture = texture;
		this.uHover = uHover;
		this.vHover = vHover;
		this.uPressed = uPressed;
		this.vPressed = vPressed;
		this.textureWidth = textureWidth;
		this.textureHeight = textureHeight;
		this.holdMillis = holdMillis;
		this.onTap = onTap;
		this.onHold = onHold;
	}

	@Override
	protected void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if(pressedAt >= 0L && !clicked(mouseX, mouseY)) { // Slid off the key: let go of it without a tap.
			pressedAt = -1L;
			held = false;
		}
		if(pressedAt >= 0L && !held && System.currentTimeMillis() - pressedAt >= holdMillis) {
			held = true;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.6f));
			onHold.run();
		}
		if(pressedAt >= 0L)
			graphics.blit(texture, getX(), getY(), uPressed, vPressed, width, height, textureWidth, textureHeight);
		else if(isHovered())
			graphics.blit(texture, getX(), getY(), uHover, vHover, width, height, textureWidth, textureHeight);
	}

	@Override
	public void onClick(double x, double y, int button) {
		if(button == GLFW_MOUSE_BUTTON_LEFT) {
			pressedAt = System.currentTimeMillis();
			held = false;
		}
	}

	@Override
	public void onRelease(double x, double y) {
		if(pressedAt < 0L)
			return;
		boolean tap = !held;
		pressedAt = -1L;
		held = false;
		if(tap) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
			onTap.run();
		}
	}

	@Override
	public void playDownSound(@NotNull net.minecraft.client.sounds.SoundManager handler) {
		// The click plays on the tap or the hold instead.
	}

	@Override
	protected void updateWidgetNarration(@NotNull NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
