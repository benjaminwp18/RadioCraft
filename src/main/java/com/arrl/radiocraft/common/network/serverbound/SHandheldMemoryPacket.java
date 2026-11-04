package com.arrl.radiocraft.common.network.serverbound;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.datacomponents.HandheldMemory;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.items.VHFHandheldItem;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * The memory channels of the handheld in inventory slot {@code index} after the holder stored one or switched between
 * VFO and memory mode. The radio's own settings follow in a {@link SHandheldRadioUpdatePacket}.
 */
public record SHandheldMemoryPacket(int index, HandheldMemory memory) implements CustomPacketPayload {

	public static final Type<SHandheldMemoryPacket> TYPE = new Type<>(Radiocraft.id("s_handheld_memory"));

	public static final StreamCodec<ByteBuf, SHandheldMemoryPacket> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SHandheldMemoryPacket::index,
			HandheldMemory.STREAM_CODEC, SHandheldMemoryPacket::memory,
			SHandheldMemoryPacket::new);

	@Override
	public @NotNull Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public void handle(IPayloadContext context) {
		context.enqueueWork(() -> {
			if(index < 0 || index >= context.player().getInventory().getContainerSize())
				return;
			ItemStack stack = context.player().getInventory().getItem(index);
			if(stack.getItem() instanceof VHFHandheldItem)
				stack.set(RadiocraftDataComponent.HANDHELD_MEMORY.value(), memory.sanitized());
		});
	}

	public static void send(int index, HandheldMemory memory) {
		PacketDistributor.sendToServer(new SHandheldMemoryPacket(index, memory));
	}
}
