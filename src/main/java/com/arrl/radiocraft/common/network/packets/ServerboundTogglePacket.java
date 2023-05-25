package com.arrl.radiocraft.common.network.packets;

import com.arrl.radiocraft.common.blockentities.ITogglableBE;
import com.arrl.radiocraft.common.network.RadiocraftPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent.Context;

import java.util.function.Supplier;

public class ServerboundTogglePacket implements RadiocraftPacket {

	private final BlockPos pos;

	public ServerboundTogglePacket(BlockPos pos) {
		this.pos = pos;
	}

	@Override
	public void encode(FriendlyByteBuf buffer) {
		buffer.writeBlockPos(pos);
	}

	public static ServerboundTogglePacket decode(FriendlyByteBuf buffer) {
		return new ServerboundTogglePacket(buffer.readBlockPos());
	}

	@Override
	public void handle(Supplier<Context> context) {
		context.get().enqueueWork(() -> {
			BlockEntity be = context.get().getSender().getLevel().getBlockEntity(pos);

			if(be instanceof ITogglableBE togglable)
				togglable.toggle();
		});
		context.get().setPacketHandled(true);
	}
}
