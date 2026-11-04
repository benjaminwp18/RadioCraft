package com.arrl.radiocraft.client.screens;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.blockentities.RepeaterAutopatchBlockEntity;
import com.arrl.radiocraft.common.menus.RepeaterAutopatchMenu;
import com.arrl.radiocraft.common.network.serverbound.SStationPacket;
import com.arrl.radiocraft.common.telephone.AutopatchControl;
import com.arrl.radiocraft.common.telephone.PabxNumbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The repeater autopatch's settings: a plain panel like the Phone PA's. Its extension (empty for the PABX to number
 * it) and activation code, saved with Save (or ENTER), and below them whether it has a repeater and what it is doing.
 * Texture from tools/repeater_autopatch_assets.py.
 */
public class RepeaterAutopatchScreen extends AbstractContainerScreen<RepeaterAutopatchMenu> {

	private static final ResourceLocation TEXTURE = Radiocraft.id("textures/gui/repeater_autopatch.png");
	private static final int WIDTH = 176, HEIGHT = 166;
	private static final int BOX_X = 8, BOX_WIDTH = 160, EXTENSION_Y = 30, CODE_Y = 62, REPEATER_Y = 84, STATE_Y = 96, SAVE_Y = 112, STATUS_Y = 138;
	private static final int INK = 0xFF404040, STATUS_INK = 0xFF303030;

	@Nullable
	private EditBox extension;
	@Nullable
	private EditBox code;

	public RepeaterAutopatchScreen(RepeaterAutopatchMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
		this.imageWidth = WIDTH;
		this.imageHeight = HEIGHT;
	}

	private RepeaterAutopatchBlockEntity autopatch() {
		return menu.blockEntity;
	}

	private static Component tr(String key, Object... args) {
		return Component.translatable(Radiocraft.translationKey("screen", "repeater_autopatch." + key), args);
	}

	@Override
	protected void init() {
		super.init();
		RepeaterAutopatchBlockEntity autopatch = autopatch();
		// Keep what was typed across a resize.
		String extensionText = extension == null ? autopatch.getExtensionText() : extension.getValue();
		String codeText = code == null ? autopatch.getCode() : code.getValue();

		extension = addRenderableWidget(new EditBox(font, leftPos + BOX_X, topPos + EXTENSION_Y, BOX_WIDTH, 16, tr("extension")));
		extension.setMaxLength(PabxNumbers.MAX_EXTENSION_DIGITS);
		extension.setFilter(s -> s.isEmpty() || PabxNumbers.digits(s));
		extension.setValue(extensionText);
		code = addRenderableWidget(new EditBox(font, leftPos + BOX_X, topPos + CODE_Y, BOX_WIDTH, 16, tr("code")));
		code.setMaxLength(AutopatchControl.MAX_CODE);
		code.setFilter(AutopatchControl::validCode);
		code.setValue(codeText);
		addRenderableWidget(Button.builder(tr("save"), b -> save()).bounds(leftPos + (WIDTH - 60) / 2, topPos + SAVE_Y, 60, 20).build());
		setInitialFocus(extension);
	}

	private void save() {
		if(extension == null || code == null)
			return;
		CompoundTag data = new CompoundTag();
		data.putString("extension", extension.getValue());
		data.putString("code", code.getValue().toUpperCase(Locale.ROOT));
		SStationPacket.send(autopatch().getBlockPos(), "save", data);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if(keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
			save();
			return true;
		}
		if(keyCode != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.isFocused()) // Typing 'e' must not close it.
			return box.keyPressed(keyCode, scanCode, modifiers) || box.canConsumeInput();
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		renderTooltip(guiGraphics, mouseX, mouseY);
	}

	@Override
	protected void renderBg(@NotNull GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
		guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
	}

	/** What it is doing now, for the line under the repeater's. */
	private Component state(RepeaterAutopatchBlockEntity autopatch) {
		return switch(autopatch.getState()) {
			case CALLING -> tr("calling", autopatch.getPartnerNumber());
			case RINGING -> tr("ringing", autopatch.getPartnerNumber());
			case IN_CALL -> tr("in_call", autopatch.getPartnerNumber());
			case IDLE -> autopatch.isArmed() ? tr("armed", autopatch.getArmedNumber())
					: autopatch.getStatus().isEmpty() ? tr("idle") : Component.literal(autopatch.getStatus());
		};
	}

	/** A label at ({@code x}, {@code y}), squeezed narrower if it would run past the box's right edge. */
	private void drawFitted(GuiGraphics guiGraphics, Component text, int x, int y) {
		int room = BOX_X + BOX_WIDTH - x;
		int width = font.width(text);
		if(width <= room) {
			guiGraphics.drawString(font, text, x, y, INK, false);
			return;
		}
		guiGraphics.pose().pushPose();
		guiGraphics.pose().translate(x, y, 0.0F);
		guiGraphics.pose().scale((float)room / width, 1.0F, 1.0F);
		guiGraphics.drawString(font, text, 0, 0, INK, false);
		guiGraphics.pose().popPose();
	}

	@Override
	protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		RepeaterAutopatchBlockEntity autopatch = autopatch();
		drawFitted(guiGraphics, title, 8, 6);
		drawFitted(guiGraphics, tr("extension_label"), BOX_X, EXTENSION_Y - 10);
		drawFitted(guiGraphics, tr("code_label", AutopatchControl.MAX_CODE), BOX_X, CODE_Y - 10);
		drawFitted(guiGraphics, autopatch.hasRepeater() ? tr("repeater") : tr("no_repeater"), BOX_X, REPEATER_Y);
		guiGraphics.drawString(font, font.plainSubstrByWidth(state(autopatch).getString(), WIDTH - 16), BOX_X, STATE_Y, INK, false);
		if(!autopatch.getStatusKey().isEmpty()) {
			int y = STATUS_Y;
			for(FormattedCharSequence line : font.split(Component.translatable(autopatch.getStatusKey(), autopatch.getStatusArg()), WIDTH - 16)) {
				guiGraphics.drawString(font, line, (WIDTH - font.width(line)) / 2, y, STATUS_INK, false);
				y += 10;
			}
		}
	}
}
