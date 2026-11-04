package com.arrl.radiocraft.client.screens.widgets;

import com.arrl.radiocraft.common.init.RadiocraftSoundEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

public class ToggleButton extends AbstractWidget {

	private final ResourceLocation resourceLocation;
	private final int u;
	private final int v;
	private final int textureWidth;
	private final int textureHeight;
	private final OnInteract onPress;

	public boolean isToggled;
	private boolean powerClick = false;

	public ToggleButton(boolean isToggled, int x, int y, int width, int height, int u, int v, ResourceLocation texLocation, int texWidth, int texHeight, OnInteract onPress) {
		super(x, y, width, height, CommonComponents.EMPTY);
		this.textureWidth = texWidth;
		this.textureHeight = texHeight;
		this.u = u;
		this.v = v;
		this.resourceLocation = texLocation;
		this.isToggled = isToggled;
		this.onPress = onPress;
	}

	@Override
	protected void renderWidget(@NotNull GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick) {
		int xBlit = !isHovered() ? u : u + width;
		int yBlit = !isToggled ? v : v + height;
		pGuiGraphics.blit(this.resourceLocation, this.getX(), this.getY(), xBlit, yBlit, width, height, textureWidth, textureHeight);
	}

	@Override
	public void onClick(double x, double y, int button) {
		super.onClick(x, y, button);
		if (button == GLFW_MOUSE_BUTTON_LEFT) {
			isToggled = !isToggled;
			onPress.execute(this);
		}
	}

	/** Makes this a power button: it latches in and pops out ({@link #playPowerClick}) instead of the menu click. */
	public ToggleButton withPowerClick() {
		powerClick = true;
		return this;
	}

	@Override
	public void playDownSound(@NotNull SoundManager handler) {
		if(powerClick)
			playPowerClick(!isToggled); // Played before the click toggles it: not toggled yet means it is going in.
		else
			super.playDownSound(handler);
	}

	/** A power button's click: latching in to turn on ({@code turningOn}), popping out to turn off. */
	public static void playPowerClick(boolean turningOn) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI((turningOn ? RadiocraftSoundEvents.POWER_IN : RadiocraftSoundEvents.POWER_OUT).get(), 1.0F));
	}

	@Override
	protected void updateWidgetNarration(@NotNull NarrationElementOutput narrationElementOutput) {
		this.defaultButtonNarrationText(narrationElementOutput);
	}

	@FunctionalInterface
	public interface OnInteract {
		void execute(ToggleButton button);
	}

}
