package com.arrl.radiocraft.common.menus;

import com.arrl.radiocraft.common.blockentities.ChargeControllerBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.init.RadiocraftMenuTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ChargeControllerMenu extends AbstractContainerMenu {

	public final ChargeControllerBlockEntity blockEntity;
	private final ContainerLevelAccess canInteractWithCallable;
	private final ContainerData data;

	public ChargeControllerMenu(final int id, final ChargeControllerBlockEntity blockEntity, ContainerData data) {
		super(RadiocraftMenuTypes.CHARGE_CONTROLLER.get(), id);
		this.blockEntity = blockEntity;
		this.canInteractWithCallable = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
		this.data = data;
		addDataSlots(this.data);
	}

	public ChargeControllerMenu(final int id, final Inventory playerInventory, final FriendlyByteBuf data) {
		this(id, getBlockEntity(playerInventory, data), new SimpleContainerData(1));
	}

	private static ChargeControllerBlockEntity getBlockEntity(final Inventory playerInventory, final FriendlyByteBuf data) {
		final BlockEntity blockEntity = playerInventory.player.level.getBlockEntity(data.readBlockPos());

		if(blockEntity instanceof ChargeControllerBlockEntity) {
			return (ChargeControllerBlockEntity)blockEntity;
		}
		throw new IllegalStateException("BlockEntity at " + data.readBlockPos() + " is not correct");
	}

	public int getPowerTick() {
		return data.get(0);
	}

	@Override
	public boolean stillValid(Player player) {
		return stillValid(canInteractWithCallable, player, RadiocraftBlocks.CHARGE_CONTROLLER.get());
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return null;
	}

}