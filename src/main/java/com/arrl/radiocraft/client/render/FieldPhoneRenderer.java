package com.arrl.radiocraft.client.render;

import com.arrl.radiocraft.common.blockentities.FieldPhoneBlockEntity;
import com.arrl.radiocraft.common.blockentities.TelephoneBlockEntity;
import com.arrl.radiocraft.common.blocks.FieldPhoneBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** The field phone's handset cord, from the case to the hand of whoever has it up. */
public class FieldPhoneRenderer implements BlockEntityRenderer<FieldPhoneBlockEntity> {

	public FieldPhoneRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(@NotNull FieldPhoneBlockEntity phone, float partialTick, @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer, int light, int overlay) {
		UUID holder = phone.getHandsetHolder();
		if(holder == null || phone.getLevel() == null)
			return;
		Player player = phone.getLevel().getPlayerByUUID(holder);
		if(player == null)
			return;
		Direction facing = phone.getBlockState().hasProperty(FieldPhoneBlock.FACING) ? phone.getBlockState().getValue(FieldPhoneBlock.FACING) : Direction.NORTH;
		Direction right = facing.getCounterClockWise(); // The cord comes out at the right hand end as you face it.
		Vec3 start = phone.getBlockPos().getCenter().add(right.getStepX() * 0.4D, 0.3D, right.getStepZ() * 0.4D);
		CordRenderer.render(phone.getLevel(), phone.getBlockPos(), start, player.getRopeHoldPosition(partialTick), poseStack, buffer);
	}

	@Override
	public @NotNull AABB getRenderBoundingBox(@NotNull FieldPhoneBlockEntity phone) {
		AABB box = new AABB(phone.getBlockPos());
		return phone.getHandsetHolder() == null ? box : box.inflate(TelephoneBlockEntity.CORD_LENGTH + 2.0D);
	}
}
