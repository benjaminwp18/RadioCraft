package com.arrl.radiocraft.common.menus;

import com.arrl.radiocraft.common.blockentities.RepeaterAutopatchBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/** The repeater autopatch's screen: its extension and activation code. No slots. */
public class RepeaterAutopatchMenu extends AbstractContainerMenu implements StationMenu {

	public final RepeaterAutopatchBlockEntity blockEntity;

	public RepeaterAutopatchMenu(int id, RepeaterAutopatchBlockEntity blockEntity) {
		super(RadiocraftMenuTypes.REPEATER_AUTOPATCH.get(), id);
		this.blockEntity = blockEntity;
	}

	public RepeaterAutopatchMenu(final int id, final Inventory playerInventory, final FriendlyByteBuf data) {
		this(id, MenuUtils.getBlockEntity(playerInventory, data, RepeaterAutopatchBlockEntity.class));
	}

	@Override
	public BlockPos getBlockPos() {
		return blockEntity.getBlockPos();
	}

	@Override
	public void handleAction(ServerPlayer player, String action, CompoundTag data) {
		if(action.equals("save"))
			blockEntity.save(data.getString("extension"), data.getString("code"));
	}

	@Override
	public boolean stillValid(@NotNull Player player) {
		return !blockEntity.isRemoved() && player.distanceToSqr(blockEntity.getBlockPos().getCenter()) <= 64.0D;
	}

	@Override
	public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
		return ItemStack.EMPTY;
	}
}
