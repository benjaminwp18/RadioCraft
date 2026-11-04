package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.common.blocks.DtmfDecoderBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.menus.DtmfDecoderMenu;
import com.arrl.radiocraft.common.radio.voice.Dtmf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The DTMF decoder: placed against a radio (or on its data wire), it listens for one code in the keys the radio copies
 * ({@link com.arrl.radiocraft.common.radio.voice.DtmfListener}). The red lamp lights once the start of the code has
 * come in, the green one when all of it has (for {@link #GREEN_TICKS}), and then it pulses or toggles its redstone
 * output. A code half sent is forgotten after {@link #TIMEOUT_TICKS} without a key. It needs no power of its own, but
 * its radio must be on.
 */
public class DtmfDecoderBlockEntity extends BlockEntity implements MenuProvider, DtmfKeyReceiver {

	/** How long the green lamp stays lit after a match: 5 seconds. */
	public static final int GREEN_TICKS = 100;
	/** How long a pulse holds the output on: a second, like a stone button. */
	public static final int PULSE_TICKS = 20;
	/** A code started and not finished within this many ticks of the last key is dropped: 5 seconds. */
	public static final int TIMEOUT_TICKS = 100;

	private String code = "";
	private boolean toggleMode = false;
	private boolean toggledOn = false;
	private String partial = ""; // The end of what was heard that starts the code.
	private String heard = ""; // The last keys heard, for the screen.
	private long lastKeyGameTime = -1L;
	private long greenUntilGameTime = -1L;
	private long pulseUntilGameTime = -1L;
	private int matches = 0;
	private boolean syncPending = false;

	public DtmfDecoderBlockEntity(BlockPos pos, BlockState state) {
		super(RadiocraftBlockEntities.DTMF_DECODER.get(), pos, state);
	}

	public static <T extends BlockEntity> void tick(Level level, BlockPos pos, BlockState state, T t) {
		if(t instanceof DtmfDecoderBlockEntity decoder && level instanceof ServerLevel serverLevel)
			decoder.serverTick(serverLevel);
	}

	private void serverTick(ServerLevel level) {
		long now = level.getGameTime();
		if(!partial.isEmpty() && now - lastKeyGameTime > TIMEOUT_TICKS) {
			partial = "";
			setChanged();
			sync();
		}
		updateBlockState();
		if(syncPending && now % 2 == 0) {
			syncPending = false;
			BlockState state = getBlockState();
			level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
		}
	}

	@Override
	public void receiveDtmf(com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity radio, char key) {
		receive(key);
	}

	/** A key its radio copied. */
	public void receive(char key) {
		if(level == null || !Dtmf.isKey(key))
			return;
		key = Character.toUpperCase(key);
		long now = level.getGameTime();
		lastKeyGameTime = now;
		heard = heard + key;
		if(heard.length() > Dtmf.MAX_DIGITS)
			heard = heard.substring(heard.length() - Dtmf.MAX_DIGITS);
		if(!code.isEmpty()) {
			partial = partial + key;
			while(!partial.isEmpty() && !code.startsWith(partial)) // The longest end of what came in that starts the code.
				partial = partial.substring(1);
			if(partial.equals(code)) {
				partial = "";
				matched(now);
			}
		}
		setChanged();
		sync();
		updateBlockState();
	}

	private void matched(long now) {
		matches++;
		greenUntilGameTime = now + GREEN_TICKS;
		if(toggleMode)
			toggledOn = !toggledOn;
		else
			pulseUntilGameTime = now + PULSE_TICKS;
	}

	/** Lamps and output in the block state, from where the code stands now. */
	private void updateBlockState() {
		if(level == null || level.isClientSide)
			return;
		long now = level.getGameTime();
		BlockState state = getBlockState();
		if(!(state.getBlock() instanceof DtmfDecoderBlock))
			return;
		BlockState wanted = state.setValue(DtmfDecoderBlock.RED, !partial.isEmpty())
				.setValue(DtmfDecoderBlock.GREEN, now < greenUntilGameTime)
				.setValue(DtmfDecoderBlock.POWERED, toggleMode ? toggledOn : now < pulseUntilGameTime);
		if(wanted != state)
			level.setBlock(worldPosition, wanted, Block.UPDATE_ALL);
	}

	// -------------------- SETTINGS --------------------

	public void setCode(String value) {
		code = Dtmf.sanitize(value);
		partial = "";
		setChanged();
		sync();
		updateBlockState();
	}

	/** Toggle (each match flips the output) or pulse (each match gives a short pulse). Changing it turns the output off. */
	public void setToggleMode(boolean value) {
		toggleMode = value;
		toggledOn = false;
		pulseUntilGameTime = -1L;
		setChanged();
		sync();
		updateBlockState();
	}

	public String getCode() {
		return code;
	}

	public boolean isToggleMode() {
		return toggleMode;
	}

	/** The last keys its radio copied, newest last. */
	public String getHeard() {
		return heard;
	}

	/** How much of the code has come in so far. */
	public String getPartial() {
		return partial;
	}

	/** How many times the whole code has come in. */
	public int getMatches() {
		return matches;
	}

	// -------------------- MENU --------------------

	@Override
	public @NotNull Component getDisplayName() {
		return Component.translatable("container.radiocraft.dtmf_decoder");
	}

	@Nullable
	@Override
	public AbstractContainerMenu createMenu(int id, @NotNull Inventory playerInventory, @NotNull Player player) {
		return new DtmfDecoderMenu(id, this);
	}

	// -------------------- SAVE & SYNC --------------------

	private void sync() {
		if(level != null && !level.isClientSide)
			syncPending = true;
	}

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("code", code);
		tag.putBoolean("toggle", toggleMode);
		tag.putBoolean("toggledOn", toggledOn);
		tag.putString("partial", partial);
		tag.putString("heard", heard);
		tag.putLong("lastKey", lastKeyGameTime);
		tag.putLong("greenUntil", greenUntilGameTime);
		tag.putLong("pulseUntil", pulseUntilGameTime);
		tag.putInt("matches", matches);
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		code = Dtmf.sanitize(tag.getString("code"));
		toggleMode = tag.getBoolean("toggle");
		toggledOn = tag.getBoolean("toggledOn");
		partial = Dtmf.sanitize(tag.getString("partial"));
		heard = Dtmf.sanitize(tag.getString("heard"));
		lastKeyGameTime = tag.contains("lastKey") ? tag.getLong("lastKey") : -1L;
		greenUntilGameTime = tag.contains("greenUntil") ? tag.getLong("greenUntil") : -1L;
		pulseUntilGameTime = tag.contains("pulseUntil") ? tag.getLong("pulseUntil") : -1L;
		matches = tag.getInt("matches");
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
