package com.arrl.radiocraft.client.render;

import com.arrl.radiocraft.client.screens.moderation.ModerationDesk;
import com.arrl.radiocraft.common.blockentities.ModerationComputerBlockEntity;
import com.arrl.radiocraft.common.blocks.ModerationComputerBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * The moderation computer's monitor showing its screen: the window as this player last left it (see
 * {@link ModerationDesk#monitor}), lit by itself, on the glass above the taskbar. Without one (another player's
 * computer, or one closed long ago) the glass shows the model's plain desktop.
 */
public class ModerationComputerRenderer implements BlockEntityRenderer<ModerationComputerBlockEntity> {

	// The glass above the taskbar on the model's north face, in model units (see tools/moderation_computer_model.py).
	private static final float GLASS_X0 = 3.75F, GLASS_X1 = 12.25F, GLASS_Y0 = 7.75F, GLASS_Y1 = 13.5F, GLASS_Z = 6.0F;
	/** A little in front of the glass, so it never fights the model's face. */
	private static final float LIFT = 0.03F;
	private static final float MARGIN = 0.2F;

	public ModerationComputerRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(ModerationComputerBlockEntity computer, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
		ResourceLocation picture = ModerationDesk.monitor(computer.getBlockPos());
		if(picture == null)
			return;
		// The picture keeps its shape, as big as fits the glass, centred.
		float aspect = ModerationDesk.MONITOR_W / (float)ModerationDesk.MONITOR_H;
		float areaW = GLASS_X1 - GLASS_X0 - MARGIN * 2, areaH = GLASS_Y1 - GLASS_Y0 - MARGIN * 2;
		float w = Math.min(areaW, areaH * aspect), h = w / aspect;
		float midX = (GLASS_X0 + GLASS_X1) / 2, midY = (GLASS_Y0 + GLASS_Y1) / 2;
		// Facing north, the viewer's left is east (+x).
		float left = (midX + w / 2) / 16, right = (midX - w / 2) / 16, bottom = (midY - h / 2) / 16, top = (midY + h / 2) / 16;
		float z = (GLASS_Z - LIFT) / 16;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F); // Turned as the blockstate turns the model (north unturned).
		poseStack.mulPose(Axis.YP.rotationDegrees(-(computer.getBlockState().getValue(ModerationComputerBlock.FACING).toYRot() + 180.0F)));
		poseStack.translate(-0.5F, 0.0F, -0.5F);
		Matrix4f pose = poseStack.last().pose();
		VertexConsumer buffer = buffers.getBuffer(RenderType.text(picture));
		// Counter-clockwise from the front; the picture's rows run bottom up, as GL keeps them.
		vertex(buffer, pose, left, bottom, z, 0.0F, 0.0F);
		vertex(buffer, pose, right, bottom, z, 1.0F, 0.0F);
		vertex(buffer, pose, right, top, z, 1.0F, 1.0F);
		vertex(buffer, pose, left, top, z, 0.0F, 1.0F);
		poseStack.popPose();
	}

	private static void vertex(VertexConsumer buffer, Matrix4f pose, float x, float y, float z, float u, float v) {
		buffer.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v).setLight(LightTexture.FULL_BRIGHT);
	}
}
