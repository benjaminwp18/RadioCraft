package com.arrl.radiocraft.client.render;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.blockentities.DeskChargerBlockEntity;
import com.arrl.radiocraft.common.items.VHFHandheldItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * GeckoLib renderer for the Desk Charger block entity.
 * <p>
 * Handles rendering of the desk charger model with dynamic LED indicators via
 * {@link BlinkingAutoGlowingGeoLayer}. A VHF handheld in the slot is drawn with its own 3D model standing in the
 * cradle, its LCD showing its main display while it is switched on; anything else in the slot shows the model's own
 * Radio bone.
 */
public class DeskChargerBlockRenderer extends GeoBlockRenderer<DeskChargerBlockEntity> {

    private static final DeskChargerModel MODEL = new DeskChargerModel();

    public DeskChargerBlockRenderer(BlockEntityRendererProvider.Context context) {
        super(MODEL);
        addRenderLayer(new BlinkingAutoGlowingGeoLayer<>(this));
    }

    public static class DeskChargerModel extends DefaultedBlockGeoModel<DeskChargerBlockEntity> {
        public DeskChargerModel() {
            super(ResourceLocation.fromNamespaceAndPath(Radiocraft.MOD_ID, "desk_charger"));
        }
    }

    @Override
    public void render(DeskChargerBlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Level level = blockEntity.getLevel();
        BlockPos pos = blockEntity.getBlockPos();

        if (level != null) {
            packedLight = LevelRenderer.getLightColor(level, pos);
        }

        // Hide the "Radio" bone if there's no radio in the charger slot, or a handheld drawn with its own model
        var stack = blockEntity.inventory.getStackInSlot(0);
        boolean handheld = stack != null && stack.getItem() instanceof VHFHandheldItem;
        try {
            var bone = MODEL.getAnimationProcessor().getBone("Radio");
            if (bone != null) {
                bone.setHidden(stack == null || stack.isEmpty() || handheld);
            }
        } catch (Exception e) {
            Radiocraft.LOGGER.debug("Could not toggle Radio bone visibility: {}", e.getMessage());
        }

        super.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        if (handheld)
            renderHandheld(blockEntity, stack, poseStack, bufferSource, packedLight, packedOverlay);
    }

    /** The handheld's model is 5 px wide at the battery; the cradle 4: drawn at this scale it drops in. */
    private static final float RADIO_SCALE = 0.8F;
    /** The cradle's floor, in blocks above the charger's base. */
    private static final float CRADLE_FLOOR = 0.5F / 16.0F;
    /** Where the bottom of the battery is in the handheld's model (model units). */
    private static final float MODEL_BOTTOM = 1.5F;

    /**
     * The handheld standing in the cradle, its front (keypad and screen) facing out of the charger's front like the
     * LED, drawn by {@link HandheldItemRenderer} (its LCD while on, lit and with its RX LED while it hears something).
     */
    private void renderHandheld(DeskChargerBlockEntity charger, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.0F, 0.5F);
        rotateBlock(getFacing(charger), poseStack);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F)); // The model's front is south; the charger's is north.
        poseStack.translate(0.0F, CRADLE_FLOOR - RADIO_SCALE * (MODEL_BOTTOM / 16.0F - 0.5F), 0.0F);
        poseStack.scale(RADIO_SCALE, RADIO_SCALE, RADIO_SCALE);
        IEnergyStorage energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        boolean charging = energy != null && energy.getEnergyStored() < energy.getMaxEnergyStored();
        HandheldItemRenderer.renderDocked(stack, charger.getBlockPos(), charging ? "CHG" : "", charger.isReceiving(), poseStack, buffers, light, overlay);
        poseStack.popPose();
    }
}