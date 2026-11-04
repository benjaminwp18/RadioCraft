package com.arrl.radiocraft.client.events;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.client.RadiocraftClientValues;
import com.arrl.radiocraft.client.RadiocraftKeys;
import com.arrl.radiocraft.common.network.serverbound.SPlayerClickHoldUpdate;
import com.arrl.radiocraft.common.network.serverbound.SWornPttPacket;
import com.arrl.radiocraft.compat.curios.CuriosCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Optional;

import static com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities.VHF_HANDHELDS;

@EventBusSubscriber(modid = Radiocraft.MOD_ID, value = Dist.CLIENT)
public class ClientTick {


    private static boolean wasUseHeld = false;
    private static boolean wasWornPttHeld = false;

    /**
     * Handles right click to PTT on VHF handhelds
     * @param event - the event
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {

        boolean isUseHeld = Minecraft.getInstance().options.keyUse.isDown();

        if(isUseHeld != wasUseHeld) {
            SPlayerClickHoldUpdate.updateServer(isUseHeld);

            boolean holdingRadio = Optional.ofNullable(Minecraft.getInstance().player).map(Player::getMainHandItem).map(i -> i.getCapability(VHF_HANDHELDS)).isPresent();
            // Holding right click also talks on a radio's hand mic, or on the desk mic being looked at: the voice chat
            // has to send for those too, or the radio keys up and carries nothing.
            boolean holdingMic = Optional.ofNullable(Minecraft.getInstance().player).map(Player::getMainHandItem)
                    .map(i -> i.getItem() instanceof com.arrl.radiocraft.common.items.CordedMicrophoneItem).orElse(false);
            boolean atDeskMic = Minecraft.getInstance().hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && Minecraft.getInstance().level != null
                    && Minecraft.getInstance().level.getBlockState(hit.getBlockPos()).getBlock() instanceof com.arrl.radiocraft.common.blocks.radios.MicrophoneBlock;

            // A screen with its own PTT (the radios' screens) runs the voice flags itself. Opening it lets go of right
            // click, and setting them here then turned the voice off under its PTT button.
            if(Minecraft.getInstance().screen == null)
                RadiocraftClientValues.SCREEN_PTT_PRESSED =
                        RadiocraftClientValues.SCREEN_VOICE_ENABLED =
                                isUseHeld && !Minecraft.getInstance().options.keyShift.isDown() && (holdingRadio || holdingMic || atDeskMic);

            if(holdingRadio && !Minecraft.getInstance().options.keyShift.isDown()) {
                if(isUseHeld) {
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                } else {
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.3f));
                }
            }
        }

        wasUseHeld = isUseHeld;

        // The "talk on worn radio" key, for a handheld worn on the belt (Curios).
        boolean wornPtt = RadiocraftKeys.WORN_PTT.isDown();
        if(wornPtt != wasWornPttHeld) {
            SWornPttPacket.send(wornPtt);
            if(CuriosCompat.isLoaded() && Minecraft.getInstance().player != null && !CuriosCompat.wornHandhelds(Minecraft.getInstance().player).isEmpty())
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, wornPtt ? 1.0F : 1.3F));
        }
        wasWornPttHeld = wornPtt;
    }
}
