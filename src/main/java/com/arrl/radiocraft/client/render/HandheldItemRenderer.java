package com.arrl.radiocraft.client.render;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability;
import com.arrl.radiocraft.client.screens.radios.HandheldLcdTexture;
import com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the VHF handheld wherever it is shown as an item (hand, belt, inventory, desk charger): its 3D model, then,
 * while it is switched on, its LCD showing the main display, and while it hears something the RX LED lit (an emissive
 * model, {@code item/vhf_handheld_rx_led}) and the backlight on. The item models stay plain JSON: at baking each is
 * wrapped ({@link #wrapModels}) so the item renderer hands the drawing to this class, which draws the original.
 */
public class HandheldItemRenderer extends BlockEntityWithoutLevelRenderer {

	/** The RX LED lit, drawn over the face's green LED. */
	public static final ModelResourceLocation RX_LED = ModelResourceLocation.standalone(Radiocraft.id("item/vhf_handheld_rx_led"));

	// The LCD's glass on the front of the model (model units; the front is the south face, z 9.25).
	private static final float LCD_X0 = 7.6F, LCD_X1 = 10.15F, LCD_Y0 = 8.4F, LCD_Y1 = 9.6F, LCD_Z = 9.25F + 0.02F;

	/** The baked models as loaded, by item, for drawing. */
	private static final Map<Item, BakedModel> ORIGINALS = new HashMap<>();

	/** A desk charger drawing the handheld in its cradle: what it says (see {@link #renderDocked}). Render thread only. */
	@Nullable
	private static Dock dock = null;

	private record Dock(Object key, String status, boolean receiving) {}

	private static HandheldItemRenderer instance;

	private HandheldItemRenderer() {
		super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
	}

	public static HandheldItemRenderer get() {
		if(instance == null)
			instance = new HandheldItemRenderer();
		return instance;
	}

	/**
	 * At model baking: each handheld's item model is wrapped to say it has a custom renderer (this one), keeping its
	 * transforms, lighting and particle; the original is kept to be drawn.
	 */
	public static void wrapModels(Map<ModelResourceLocation, BakedModel> models) {
		ORIGINALS.clear();
		for(Item item : RadiocraftItems.VHF_HANDHELD_VARIANTS.values().toArray(Item[]::new)) {
			ModelResourceLocation id = ModelResourceLocation.inventory(BuiltInRegistries.ITEM.getKey(item));
			BakedModel original = models.get(id);
			if(original == null)
				continue;
			ORIGINALS.put(item, original);
			models.put(id, new BakedModelWrapper<>(original) {
				@Override
				public boolean isCustomRenderer() {
					return true;
				}

				/** The original's display transform, but still this model: the item renderer draws what this returns. */
				@Override
				public @NotNull BakedModel applyTransform(@NotNull ItemDisplayContext context, @NotNull PoseStack poseStack, boolean leftHand) {
					originalModel.applyTransform(context, poseStack, leftHand);
					return this;
				}
			});
		}
	}

	/**
	 * Draws {@code stack} standing in a desk charger: the charger knows whether the radio hears something and whether
	 * it is charging, which the item does not. {@code key} names the charger's LCD texture.
	 */
	public static void renderDocked(ItemStack stack, Object key, String status, boolean receiving, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
		dock = new Dock(key, status, receiving);
		try {
			Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.NONE, light, overlay, poseStack, buffers, Minecraft.getInstance().level, 0);
		}
		finally {
			dock = null;
		}
	}

	@Override
	public void renderByItem(@NotNull ItemStack stack, @NotNull ItemDisplayContext context, @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffers, int light, int overlay) {
		BakedModel original = ORIGINALS.get(stack.getItem());
		if(original == null)
			return;
		// The item renderer has applied the display transform and moved to the model's corner; drawing the original
		// as a plain item (no transform) moves there again, so go back to its centre first.
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		Minecraft.getInstance().getItemRenderer().render(stack, ItemDisplayContext.NONE, false, poseStack, buffers, light, overlay, original);
		poseStack.popPose();

		IVHFHandheldCapability cap = stack.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
		if(cap == null || !cap.isPowered())
			return;
		Dock docked = dock;
		boolean receiving = docked != null ? docked.receiving() : cap.getReceiveStrength() > 0.0F;
		String status = receiving ? "RX" : docked != null ? docked.status() : cap.isPTTDown() ? "TX" : "";
		ResourceLocation lcd = HandheldLcdTexture.paint(docked != null ? docked.key() : stack, stack, status, receiving);
		PoseStack.Pose pose = poseStack.last();
		VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(lcd));
		float x0 = LCD_X0 / 16.0F, x1 = LCD_X1 / 16.0F, y0 = LCD_Y0 / 16.0F, y1 = LCD_Y1 / 16.0F, z = LCD_Z / 16.0F;
		vertex(consumer, pose, x0, y0, z, 0.0F, 1.0F, light, overlay);
		vertex(consumer, pose, x1, y0, z, 1.0F, 1.0F, light, overlay);
		vertex(consumer, pose, x1, y1, z, 1.0F, 0.0F, light, overlay);
		vertex(consumer, pose, x0, y1, z, 0.0F, 0.0F, light, overlay);
		if(receiving) { // Its quads carry full light (neoforge_data), so the LED glows in the dark.
			BakedModel led = Minecraft.getInstance().getModelManager().getModel(RX_LED);
			Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(pose, buffers.getBuffer(Sheets.cutoutBlockSheet()), null, led,
					1.0F, 1.0F, 1.0F, light, overlay, ModelData.EMPTY, Sheets.cutoutBlockSheet());
		}
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int light, int overlay) {
		consumer.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(pose, 0.0F, 0.0F, 1.0F);
	}
}
