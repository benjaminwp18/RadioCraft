package com.arrl.radiocraft.common.menus;

import com.arrl.radiocraft.common.blockentities.DtmfDecoderBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.init.RadiocraftMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** The DTMF decoder's screen: the code it listens for, and pulse or toggle. No slots. */
public class DtmfDecoderMenu extends AbstractContainerMenu implements StationMenu {

	public final DtmfDecoderBlockEntity blockEntity;
	private final ContainerLevelAccess access;

	public DtmfDecoderMenu(int id, DtmfDecoderBlockEntity blockEntity) {
		super(RadiocraftMenuTypes.DTMF_DECODER.get(), id);
		this.blockEntity = blockEntity;
		this.access = ContainerLevelAccess.create(Objects.requireNonNull(blockEntity.getLevel()), blockEntity.getBlockPos());
	}

	public DtmfDecoderMenu(final int id, final Inventory playerInventory, final FriendlyByteBuf data) {
		this(id, MenuUtils.getBlockEntity(playerInventory, data, DtmfDecoderBlockEntity.class));
	}

	@Override
	public BlockPos getBlockPos() {
		return blockEntity.getBlockPos();
	}

	@Override
	public void handleAction(ServerPlayer player, String action, CompoundTag data) {
		switch(action) {
			case "code" -> blockEntity.setCode(data.getString("code"));
			case "toggle" -> blockEntity.setToggleMode(data.getBoolean("toggle"));
			default -> {}
		}
	}

	@Override
	public boolean stillValid(@NotNull Player player) {
		return stillValid(access, player, RadiocraftBlocks.DTMF_DECODER.get());
	}

	@Override
	public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
		return ItemStack.EMPTY;
	}
}
