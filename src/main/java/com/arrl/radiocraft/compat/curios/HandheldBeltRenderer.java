package com.arrl.radiocraft.compat.curios;

import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * A VHF handheld worn in the Curios belt slot, drawn clipped to the back of the belt over where the left back pocket
 * would be, standing straight with its face to the back, following the body as it turns and sneaks. Client
 * only; only loaded with Curios.
 */
public class HandheldBeltRenderer implements ICurioRenderer {

	/** The handheld's model at this size is about 8 pixels tall, without the antenna. */
	private static final float SCALE = 0.42F;
	/**
	 * Where it hangs on the body (pixels from the neck, model space: +x the wearer's left, +y down, +z their back): its
	 * centre over the left back pocket, halfway between the spine and the side, at the belt, its clip against the back
	 * (the body's back is at 2, the radio about 1 deep at this size).
	 */
	private static final float X = 1.7F, Y = 12.5F, Z = 2.55F;

	/** Draws every crystal quality of the handheld on the belt. Call once at client setup. */
	public static void register() {
		RadiocraftItems.VHF_HANDHELD_VARIANTS.values().forEach(item -> CuriosRendererRegistry.register(item, HandheldBeltRenderer::new));
	}

	@Override
	public <T extends LivingEntity, M extends EntityModel<T>> void render(@NotNull ItemStack stack, @NotNull SlotContext slotContext, @NotNull PoseStack poseStack,
			@NotNull RenderLayerParent<T, M> parent, @NotNull MultiBufferSource buffers, int light, float limbSwing, float limbSwingAmount, float partialTicks,
			float ageInTicks, float netHeadYaw, float headPitch) {
		LivingEntity entity = slotContext.entity();
		poseStack.pushPose();
		if(parent.getModel() instanceof HumanoidModel<?> humanoid)
			humanoid.body.translateAndRotate(poseStack); // Leans and turns with the body (crouching included).
		else {
			ICurioRenderer.translateIfSneaking(poseStack, entity);
			ICurioRenderer.rotateIfSneaking(poseStack, entity);
		}
		poseStack.translate(X / 16.0F, Y / 16.0F, Z / 16.0F);
		poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F)); // Entity models are upside down.
		poseStack.scale(SCALE, SCALE, SCALE);
		Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, poseStack, buffers, entity.level(), 0);
		poseStack.popPose();
	}
}
