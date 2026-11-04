package com.arrl.radiocraft.client.events;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftClientConfig;
import com.arrl.radiocraft.common.network.serverbound.SRadioScrollTunePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Sneak and turn the mouse wheel while looking at a radio to tune it, a channel step per notch, without opening its
 * screen (client setting {@code scroll_tuning}). The wheel then leaves the hotbar alone; looking at anything else, or
 * not sneaking, it works as usual. Small trackpad scrolls add up to whole notches.
 */
@EventBusSubscriber(modid = Radiocraft.MOD_ID, value = Dist.CLIENT)
public final class ScrollTuning {

	private ScrollTuning() {}

	private static double pending = 0.0D;
	private static BlockPos lastPos = null;

	@SubscribeEvent
	public static void onScroll(InputEvent.MouseScrollingEvent event) {
		Minecraft minecraft = Minecraft.getInstance();
		if(!RadiocraftClientConfig.SCROLL_TUNING.get() || minecraft.screen != null || minecraft.player == null || minecraft.level == null
				|| !minecraft.player.isShiftKeyDown() || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
			return;
		BlockPos pos = hit.getBlockPos();
		if(!SRadioScrollTunePacket.tunes(minecraft.level.getBlockEntity(pos)))
			return;
		event.setCanceled(true); // Not the hotbar: the dial.
		if(!pos.equals(lastPos)) {
			pending = 0.0D;
			lastPos = pos;
		}
		pending += event.getScrollDeltaY();
		int steps = (int)pending;
		if(steps == 0)
			return;
		pending -= steps;
		SRadioScrollTunePacket.send(pos, steps);
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6F, 0.25F));
	}
}
