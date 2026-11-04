package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.common.be_networks.DataWire;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.menus.DtmfEncoderMenu;
import com.arrl.radiocraft.common.radio.voice.Dtmf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The DTMF encoder: a keypad placed against a radio (or on its data wire). The number keyed in goes out as DTMF tones
 * on every radio linked to it that is switched on and can transmit ({@link RadioBlockEntity#sendDtmf}). It keeps the
 * last number sent, to send again: SEND with nothing keyed in, or a redstone signal coming on.
 */
public class DtmfEncoderBlockEntity extends BlockEntity implements MenuProvider {

	private String lastSent = "";

	public DtmfEncoderBlockEntity(BlockPos pos, BlockState state) {
		super(RadiocraftBlockEntities.DTMF_ENCODER.get(), pos, state);
	}

	/** Radios on any side of the encoder, or on its data wire. */
	public List<RadioBlockEntity> getRadios() {
		if(level == null)
			return List.of();
		return DataWire.linked(level, worldPosition, RadioBlockEntity.class);
	}

	/**
	 * Sends {@code digits} (cleaned up by {@link Dtmf#sanitize}) on the linked radios.
	 *
	 * @return How many radios send it: 0 if none is on and able to.
	 */
	public int send(String digits) {
		String keys = Dtmf.sanitize(digits);
		if(keys.isEmpty() || level == null || level.isClientSide)
			return 0;
		int sent = 0;
		for(RadioBlockEntity radio : getRadios())
			if(radio.sendDtmf(keys))
				sent++;
		lastSent = keys;
		setChanged();
		BlockState state = getBlockState();
		level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		return sent;
	}

	private boolean wasPowered = false; // Server only, not saved: a redstone signal coming on sends once.

	/** The redstone signal into the encoder is {@code powered}: coming on, it sends the last number again. */
	public void redstone(boolean powered) {
		if(powered && !wasPowered && !lastSent.isEmpty())
			send(lastSent);
		wasPowered = powered;
	}

	public String getLastSent() {
		return lastSent;
	}

	@Override
	public @NotNull Component getDisplayName() {
		return Component.translatable("container.radiocraft.dtmf_encoder");
	}

	@Nullable
	@Override
	public AbstractContainerMenu createMenu(int id, @NotNull Inventory playerInventory, @NotNull Player player) {
		return new DtmfEncoderMenu(id, this);
	}

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("lastSent", lastSent);
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		lastSent = Dtmf.sanitize(tag.getString("lastSent"));
	}

	@Override
	public @NotNull CompoundTag getUpdateTag(@NotNull HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
