package com.arrl.radiocraft.common.menus;

import com.arrl.radiocraft.common.blockentities.DtmfEncoderBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.init.RadiocraftMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** The DTMF encoder's keypad: SEND puts the number keyed in on the radios. No slots. */
public class DtmfEncoderMenu extends AbstractContainerMenu implements StationMenu {

	public final DtmfEncoderBlockEntity blockEntity;
	private final ContainerLevelAccess access;

	public DtmfEncoderMenu(int id, DtmfEncoderBlockEntity blockEntity) {
		super(RadiocraftMenuTypes.DTMF_ENCODER.get(), id);
		this.blockEntity = blockEntity;
		this.access = ContainerLevelAccess.create(Objects.requireNonNull(blockEntity.getLevel()), blockEntity.getBlockPos());
	}

	public DtmfEncoderMenu(final int id, final Inventory playerInventory, final FriendlyByteBuf data) {
		this(id, MenuUtils.getBlockEntity(playerInventory, data, DtmfEncoderBlockEntity.class));
	}

	@Override
	public BlockPos getBlockPos() {
		return blockEntity.getBlockPos();
	}

	@Override
	public void handleAction(ServerPlayer player, String action, CompoundTag data) {
		if(action.equals("send") && blockEntity.send(data.getString("digits")) == 0)
			player.displayClientMessage(Component.translatable("message.radiocraft.dtmf_encoder.no_radio"), true);
	}

	@Override
	public boolean stillValid(@NotNull Player player) {
		return stillValid(access, player, RadiocraftBlocks.DTMF_ENCODER.get());
	}

	@Override
	public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
		return ItemStack.EMPTY;
	}
}
