package com.arrl.radiocraft.common.network.serverbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.blockentities.VHFRepeaterBlockEntity;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * The player turned the mouse wheel while sneaking and looking at the radio at {@code pos}: it moves {@code steps}
 * channel steps (negative is down). Only a radio that is on, within reach; the new frequency shows above the hotbar.
 */
public record SRadioScrollTunePacket(BlockPos pos, int steps) implements CustomPacketPayload {

	public static final Type<SRadioScrollTunePacket> TYPE = new Type<>(Radiocraft.id("s_radio_scroll_tune"));

	/** Most steps one packet moves: a fast spin of the wheel. */
	private static final int MAX_STEPS = 20;

	public static final StreamCodec<ByteBuf, SRadioScrollTunePacket> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, SRadioScrollTunePacket::pos,
			ByteBufCodecs.VAR_INT, SRadioScrollTunePacket::steps,
			SRadioScrollTunePacket::new);

	@Override
	public @NotNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** True for a radio that sneak-scrolling tunes: not a repeater, whose input and output go together. */
	public static boolean tunes(Object blockEntity) {
		return blockEntity instanceof RadioBlockEntity && !(blockEntity instanceof VHFRepeaterBlockEntity);
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			Player player = context.player();
			if(steps == 0 || !player.level().isLoaded(pos) || player.distanceToSqr(pos.getCenter()) > player.blockInteractionRange() * player.blockInteractionRange() + 16.0D)
				return;
			if(!tunes(player.level().getBlockEntity(pos)) || !(player.level().getBlockEntity(pos) instanceof RadioBlockEntity radio) || !radio.isPowered())
				return;
			radio.updateFrequency(Math.max(-MAX_STEPS, Math.min(MAX_STEPS, steps)));
			player.displayClientMessage(Component.translatable(Radiocraft.translationKey("message", "scroll_tuned"), String.format("%.3f", radio.getFrequency() / 1_000_000.0D)), true);
		});
	}

	public static void send(BlockPos pos, int steps) {
		PacketDistributor.sendToServer(new SRadioScrollTunePacket(pos, steps));
	}
}
