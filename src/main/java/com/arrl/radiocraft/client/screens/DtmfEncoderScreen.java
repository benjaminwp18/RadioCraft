package com.arrl.radiocraft.client.screens;

import com.arrl.radiocraft.common.blockentities.DtmfEncoderBlockEntity;
import com.arrl.radiocraft.common.menus.DtmfEncoderMenu;
import com.arrl.radiocraft.common.network.serverbound.SStationPacket;
import com.arrl.radiocraft.common.radio.voice.Dtmf;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * The DTMF encoder's keypad: a telephone's twelve keys, the number keyed so far, DEL, CLR, REDIAL (the last number
 * sent) and SEND. Keys can be typed too (Enter sends, Backspace deletes). A plain panel until it has art.
 */
public class DtmfEncoderScreen extends AbstractContainerScreen<DtmfEncoderMenu> {

	private static final int PANEL = 0xFF2A2F33;
	private static final int PANEL_EDGE = 0xFF151819;
	private static final int LABEL = 0xFFBFE8C4;
	private static final int LCD = 0xFF9DB58A;
	private static final int LCD_TEXT = 0xFF1E2A18;
	private static final String[] PAD = { "123", "456", "789", "*0#" };

	private String number = "";

	public DtmfEncoderScreen(DtmfEncoderMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
		this.imageWidth = 176;
		this.imageHeight = 150;
	}

	private DtmfEncoderBlockEntity encoder() {
		return menu.blockEntity;
	}

	@Override
	protected void init() {
		super.init();
		for(int row = 0; row < PAD.length; row++)
			for(int column = 0; column < 3; column++) {
				char key = PAD[row].charAt(column);
				addRenderableWidget(Button.builder(Component.literal(String.valueOf(key)), btn -> press(key))
						.bounds(leftPos + 10 + column * 34, topPos + 44 + row * 24, 30, 20).build());
			}
		addRenderableWidget(Button.builder(Component.translatable("screen.radiocraft.dtmf_encoder.del"), btn -> delete())
				.bounds(leftPos + 116, topPos + 44, 50, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.radiocraft.dtmf_encoder.clear"), btn -> number = "")
				.bounds(leftPos + 116, topPos + 68, 50, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.radiocraft.dtmf_encoder.redial"), btn -> number = encoder().getLastSent())
				.bounds(leftPos + 116, topPos + 92, 50, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.radiocraft.dtmf_encoder.send"), btn -> send())
				.bounds(leftPos + 116, topPos + 116, 50, 20).build());
	}

	private void press(char key) {
		if(number.length() < Dtmf.MAX_DIGITS)
			number = number + key;
	}

	private void delete() {
		if(!number.isEmpty())
			number = number.substring(0, number.length() - 1);
	}

	/** Sends the number keyed in; with none, the last number sent again (the one shown). */
	private void send() {
		String digits = number.isEmpty() ? encoder().getLastSent() : number;
		if(digits.isEmpty())
			return;
		CompoundTag tag = new CompoundTag();
		tag.putString("digits", digits);
		SStationPacket.send(encoder().getBlockPos(), "send", tag);
		number = "";
	}

	@Override
	public boolean charTyped(char codePoint, int modifiers) {
		if(Dtmf.isKey(codePoint)) {
			press(Character.toUpperCase(codePoint));
			return true;
		}
		return super.charTyped(codePoint, modifiers);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if(keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			send();
			return true;
		}
		if(keyCode == GLFW.GLFW_KEY_BACKSPACE) {
			delete();
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
		guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_EDGE);
		guiGraphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, PANEL);
		guiGraphics.fill(leftPos + 10, topPos + 20, leftPos + imageWidth - 10, topPos + 38, LCD);
	}

	@Override
	protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		guiGraphics.drawString(font, title, 10, 8, LABEL, false);
		String shown = number.isEmpty() ? (encoder().getLastSent().isEmpty() ? "" : Component.translatable("screen.radiocraft.dtmf_encoder.last", encoder().getLastSent()).getString()) : number;
		guiGraphics.drawString(font, shown, 14, 25, LCD_TEXT, false);
	}
}
