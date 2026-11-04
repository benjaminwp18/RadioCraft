package com.arrl.radiocraft.client.screens;

import com.arrl.radiocraft.common.blockentities.DtmfDecoderBlockEntity;
import com.arrl.radiocraft.common.blocks.DtmfDecoderBlock;
import com.arrl.radiocraft.common.menus.DtmfDecoderMenu;
import com.arrl.radiocraft.common.network.serverbound.SStationPacket;
import com.arrl.radiocraft.common.radio.voice.Dtmf;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * The DTMF decoder's screen: the code it listens for (Enter or SAVE sets it), pulse or toggle, its two lamps and the
 * keys heard lately. A plain panel until it has art.
 */
public class DtmfDecoderScreen extends AbstractContainerScreen<DtmfDecoderMenu> {

	private static final int PANEL = 0xFF2A2F33;
	private static final int PANEL_EDGE = 0xFF151819;
	private static final int LABEL = 0xFFBFE8C4;
	private static final int DIM = 0xFF7E9A86;
	private static final int RED_ON = 0xFFFF3B30, RED_OFF = 0xFF4A1C1A;
	private static final int GREEN_ON = 0xFF3BFF5A, GREEN_OFF = 0xFF1A3A20;

	private EditBox codeBox;
	private Button modeButton;

	public DtmfDecoderScreen(DtmfDecoderMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
		this.imageWidth = 176;
		this.imageHeight = 116;
	}

	private DtmfDecoderBlockEntity decoder() {
		return menu.blockEntity;
	}

	@Override
	protected void init() {
		super.init();
		codeBox = new EditBox(font, leftPos + 10, topPos + 30, 100, 16, Component.translatable("screen.radiocraft.dtmf_decoder.code"));
		codeBox.setMaxLength(Dtmf.MAX_DIGITS);
		codeBox.setFilter(text -> text.chars().allMatch(c -> Dtmf.isKey((char)c)));
		codeBox.setValue(decoder().getCode());
		codeBox.setTooltip(Tooltip.create(Component.translatable("screen.radiocraft.dtmf_decoder.code_hint")));
		addRenderableWidget(codeBox);
		addRenderableWidget(Button.builder(Component.translatable("screen.radiocraft.dtmf_decoder.save"), btn -> saveCode())
				.bounds(leftPos + 116, topPos + 29, 50, 18).build());
		modeButton = Button.builder(modeLabel(), btn -> {
					CompoundTag tag = new CompoundTag();
					tag.putBoolean("toggle", !decoder().isToggleMode());
					SStationPacket.send(decoder().getBlockPos(), "toggle", tag);
				})
				.bounds(leftPos + 10, topPos + 54, 156, 18)
				.tooltip(Tooltip.create(Component.translatable("screen.radiocraft.dtmf_decoder.tip.mode")))
				.build();
		addRenderableWidget(modeButton);
	}

	private Component modeLabel() {
		return Component.translatable(decoder().isToggleMode() ? "screen.radiocraft.dtmf_decoder.toggle" : "screen.radiocraft.dtmf_decoder.pulse");
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		modeButton.setMessage(modeLabel()); // Follows the block once the server has switched it.
	}

	private void saveCode() {
		CompoundTag tag = new CompoundTag();
		tag.putString("code", codeBox.getValue());
		SStationPacket.send(decoder().getBlockPos(), "code", tag);
		codeBox.setFocused(false);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if(codeBox.isFocused()) {
			if(keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				saveCode();
				return true;
			}
			if(keyCode != GLFW.GLFW_KEY_ESCAPE) // Typing 'e' must not close the screen.
				return codeBox.keyPressed(keyCode, scanCode, modifiers) || codeBox.canConsumeInput();
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
		guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_EDGE);
		guiGraphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, PANEL);
	}

	@Override
	protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		guiGraphics.drawString(font, title, 10, 8, LABEL, false);
		guiGraphics.drawString(font, Component.translatable("screen.radiocraft.dtmf_decoder.code"), 10, 20, DIM, false);
		BlockState state = decoder().getBlockState();
		boolean red = state.hasProperty(DtmfDecoderBlock.RED) && state.getValue(DtmfDecoderBlock.RED);
		boolean green = state.hasProperty(DtmfDecoderBlock.GREEN) && state.getValue(DtmfDecoderBlock.GREEN);
		lamp(guiGraphics, 10, 80, red ? RED_ON : RED_OFF);
		guiGraphics.drawString(font, Component.translatable("screen.radiocraft.dtmf_decoder.receiving"), 22, 81, red ? LABEL : DIM, false);
		lamp(guiGraphics, 92, 80, green ? GREEN_ON : GREEN_OFF);
		guiGraphics.drawString(font, Component.translatable("screen.radiocraft.dtmf_decoder.match"), 104, 81, green ? LABEL : DIM, false);
		String heard = decoder().getHeard();
		guiGraphics.drawString(font, Component.translatable("screen.radiocraft.dtmf_decoder.heard", heard.isEmpty() ? "-" : heard), 10, 98, DIM, false);
	}

	private static void lamp(GuiGraphics guiGraphics, int x, int y, int colour) {
		guiGraphics.fill(x, y, x + 9, y + 9, PANEL_EDGE);
		guiGraphics.fill(x + 1, y + 1, x + 8, y + 8, colour);
	}
}
