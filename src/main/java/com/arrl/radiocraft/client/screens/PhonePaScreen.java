package com.arrl.radiocraft.client.screens;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.blockentities.PhonePaBlockEntity;
import com.arrl.radiocraft.common.menus.PhonePaMenu;
import com.arrl.radiocraft.common.network.serverbound.SStationPacket;
import com.arrl.radiocraft.common.telephone.PabxNumbers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The Phone PA's settings: a plain panel at the normal GUI size, like the PABX's, so JEI sits beside it. Its extension
 * (empty for the PABX to number it) and whether it takes local calls only, saved with Save (or ENTER). Texture from
 * tools/phone_pa_assets.py.
 */
public class PhonePaScreen extends AbstractContainerScreen<PhonePaMenu> {

	private static final ResourceLocation TEXTURE = Radiocraft.id("textures/gui/phone_pa.png");
	private static final int WIDTH = 176, HEIGHT = 138;
	private static final int BOX_X = 8, BOX_WIDTH = 160, EXTENSION_Y = 30, SPEAKERS_Y = 49, CHECK_Y = 60, SAVE_Y = 86, STATUS_Y = 110;
	private static final int INK = 0xFF404040, STATUS_INK = 0xFF303030;

	@Nullable
	private EditBox extension;
	@Nullable
	private Checkbox localOnly;

	public PhonePaScreen(PhonePaMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
		this.imageWidth = WIDTH;
		this.imageHeight = HEIGHT;
	}

	private PhonePaBlockEntity pa() {
		return menu.blockEntity;
	}

	private static Component tr(String key, Object... args) {
		return Component.translatable(Radiocraft.translationKey("screen", "phone_pa." + key), args);
	}

	@Override
	protected void init() {
		super.init();
		PhonePaBlockEntity pa = pa();
		// Keep what was typed across a resize.
		String extensionText = extension == null ? pa.getExtensionText() : extension.getValue();
		boolean localValue = localOnly == null ? pa.isLocalOnly() : localOnly.selected();

		extension = addRenderableWidget(new EditBox(font, leftPos + BOX_X, topPos + EXTENSION_Y, BOX_WIDTH, 16, tr("extension")));
		extension.setMaxLength(PabxNumbers.MAX_EXTENSION_DIGITS);
		extension.setFilter(s -> s.isEmpty() || PabxNumbers.digits(s));
		extension.setValue(extensionText);
		// Its label is drawn with the panel's other text: a checkbox's own is too pale on grey.
		localOnly = addRenderableWidget(Checkbox.builder(Component.empty(), font).pos(leftPos + BOX_X, topPos + CHECK_Y).selected(localValue).build());
		addRenderableWidget(Button.builder(tr("save"), b -> save()).bounds(leftPos + (WIDTH - 60) / 2, topPos + SAVE_Y, 60, 20).build());
		setInitialFocus(extension);
	}

	private void save() {
		if(extension == null || localOnly == null)
			return;
		CompoundTag data = new CompoundTag();
		data.putString("extension", extension.getValue());
		data.putBoolean("localOnly", localOnly.selected());
		SStationPacket.send(pa().getBlockPos(), "save", data);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if(keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			save();
			return true;
		}
		if(keyCode != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.isFocused()) // Typing 'e' must not close it.
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

	@Override
	protected void renderLabels(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		PhonePaBlockEntity pa = pa();
		guiGraphics.drawString(font, title, 8, 6, INK, false);
		guiGraphics.drawString(font, tr("extension_label"), BOX_X, EXTENSION_Y - 10, INK, false);
		guiGraphics.drawString(font, tr("speakers", pa.getSpeakerCount()), BOX_X, SPEAKERS_Y, INK, false);
		guiGraphics.drawString(font, tr("local_only"), BOX_X + 22, CHECK_Y + 5, INK, false);
		if(!pa.getStatusKey().isEmpty()) {
			int y = STATUS_Y;
			for(FormattedCharSequence line : font.split(Component.translatable(pa.getStatusKey(), pa.getStatusArg()), WIDTH - 16)) {
				guiGraphics.drawString(font, line, (WIDTH - font.width(line)) / 2, y, STATUS_INK, false);
				y += 10;
			}
		}
	}
}
