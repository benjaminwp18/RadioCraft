package com.arrl.radiocraft.common.menus;

import com.arrl.radiocraft.common.blockentities.ModerationComputerBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftMenuTypes;
import com.arrl.radiocraft.common.moderation.ComputerRequests;
import com.arrl.radiocraft.common.moderation.ModerationPlayback;
import com.arrl.radiocraft.common.network.clientbound.CModerationPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The moderation computer's screen. No slots: the screen asks for what it shows with {@link
 * com.arrl.radiocraft.common.network.serverbound.SStationPacket} requests, answered by {@link ComputerRequests} with
 * {@link CModerationPacket}s. Every request checks the lock again, and the menu closes once its user is no longer the
 * owner or an admin, or walks away.
 */
public class ModerationComputerMenu extends AbstractContainerMenu implements StationMenu {

	/** How far from the computer (squared, blocks) its user may stand. */
	private static final double REACH_SQR = 64.0D;

	public final ModerationComputerBlockEntity blockEntity;

	public ModerationComputerMenu(int id, ModerationComputerBlockEntity blockEntity) {
		super(RadiocraftMenuTypes.MODERATION_COMPUTER.get(), id);
		this.blockEntity = blockEntity;
	}

	public ModerationComputerMenu(final int id, final Inventory playerInventory, final FriendlyByteBuf data) {
		this(id, MenuUtils.getBlockEntity(playerInventory, data, ModerationComputerBlockEntity.class));
	}

	@Override
	public BlockPos getBlockPos() {
		return blockEntity.getBlockPos();
	}

	@Override
	public void handleAction(ServerPlayer player, String action, CompoundTag data) {
		if(!stillValid(player)) { // Locked out since it opened (removed as an admin, or the lock cleared).
			CModerationPacket.send(player, new ComputerRequests.Reply("denied", new CompoundTag()));
			player.closeContainer();
			return;
		}
		ComputerRequests.handle(blockEntity, player, action, data, reply -> CModerationPacket.send(player, reply));
	}

	@Override
	public boolean stillValid(@NotNull Player player) {
		if(player.level().isClientSide)
			return !blockEntity.isRemoved(); // The client does not know the lock; the server closes the menu.
		return !blockEntity.isRemoved() && player.distanceToSqr(blockEntity.getBlockPos().getCenter()) <= REACH_SQR && blockEntity.canOpen(player);
	}

	/** Closing the screen stops any playback (listening to a player live carries on until stopped). */
	@Override
	public void removed(@NotNull Player player) {
		super.removed(player);
		if(player instanceof ServerPlayer serverPlayer)
			ModerationPlayback.stop(serverPlayer.getUUID());
	}

	@Override
	public @NotNull ItemStack quickMoveStack(@NotNull Player player, int index) {
		return ItemStack.EMPTY;
	}
}
