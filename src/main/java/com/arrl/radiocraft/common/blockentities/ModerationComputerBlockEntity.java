package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.common.menus.ModerationComputerMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Radio Moderation Computer (maintainer's design, 2026-11-04; see todo.md): the screen over the moderation log. It is
 * locked to whoever placed it (their UUID and name kept here); the owner adds other admins from its Access page, and
 * only the owner and those admins can open it. {@code /radio moderation unlock <pos>} clears the lock so it is never
 * stranded; the next operator to use it then owns it.
 * <p>
 * Claiming one that has no owner (placing it, or using it after an unlock) takes {@link #CLAIM_PERMISSION}, as the
 * {@code /radio moderation} commands do, or being the single player world's host: a creative player on a server who is
 * not an operator cannot make one theirs.
 */
public class ModerationComputerBlockEntity extends BlockEntity implements MenuProvider {

	/** Operator level that may claim a computer nobody owns: the same as {@code /radio moderation}. */
	public static final int CLAIM_PERMISSION = 3;
	/** Most admins one computer keeps besides its owner. */
	public static final int MAX_ADMINS = 32;

	@Nullable
	private UUID owner;
	private String ownerName = "";
	/** Added admins: UUID to the name they had when added. */
	private final Map<UUID, String> admins = new LinkedHashMap<>();

	public ModerationComputerBlockEntity(BlockPos pos, BlockState state) {
		super(RadiocraftBlockEntities.MODERATION_COMPUTER.get(), pos, state);
	}

	@Nullable
	public UUID getOwner() {
		return owner;
	}

	public String getOwnerName() {
		return ownerName;
	}

	/** The added admins, UUID to name, in the order they were added. A copy. */
	public Map<UUID, String> getAdmins() {
		return new LinkedHashMap<>(admins);
	}

	public boolean isOwner(Player player) {
		return owner != null && owner.equals(player.getUUID());
	}

	/** True if {@code player} may open it: its owner or an admin they added. */
	public boolean canOpen(Player player) {
		return owner != null && (owner.equals(player.getUUID()) || admins.containsKey(player.getUUID()));
	}

	/** True if {@code player} may claim a computer nobody owns (see the class comment). */
	public static boolean mayClaim(ServerPlayer player) {
		return player.hasPermissions(CLAIM_PERMISSION) || player.server.isSingleplayerOwner(player.getGameProfile());
	}

	/** Makes it {@code player}'s if nobody owns it and they may claim it; true if it is theirs now. */
	public boolean claim(ServerPlayer player) {
		if(owner == null && mayClaim(player))
			setOwner(player.getUUID(), player.getGameProfile().getName());
		return isOwner(player);
	}

	/** Sets the owner outright (placing it, handing it over, tests). They stop being an added admin. */
	public void setOwner(UUID uuid, String name) {
		owner = uuid;
		ownerName = name;
		admins.remove(uuid);
		setChanged();
	}

	/** Adds an admin; false if they are the owner, already one, or the list is full. */
	public boolean addAdmin(UUID uuid, String name) {
		if(uuid.equals(owner) || admins.containsKey(uuid) || admins.size() >= MAX_ADMINS)
			return false;
		admins.put(uuid, name);
		setChanged();
		return true;
	}

	/** Removes an admin; false if they were not one. */
	public boolean removeAdmin(UUID uuid) {
		boolean removed = admins.remove(uuid) != null;
		if(removed)
			setChanged();
		return removed;
	}

	/** Hands it to {@code uuid}; the old owner stays on as an admin (the new owner may remove them). */
	public void transferTo(UUID uuid, String name) {
		UUID previous = owner;
		String previousName = ownerName;
		setOwner(uuid, name);
		if(previous != null && !previous.equals(uuid))
			admins.put(previous, previousName);
		setChanged();
	}

	/** Clears the lock: no owner and no admins, until an operator claims it. */
	public void unlock() {
		owner = null;
		ownerName = "";
		admins.clear();
		setChanged();
	}

	@Override
	protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.saveAdditional(tag, registries);
		if(owner != null) {
			tag.putUUID("owner", owner);
			tag.putString("ownerName", ownerName);
		}
		ListTag list = new ListTag();
		for(Map.Entry<UUID, String> admin : admins.entrySet()) {
			CompoundTag entry = new CompoundTag();
			entry.putUUID("uuid", admin.getKey());
			entry.putString("name", admin.getValue());
			list.add(entry);
		}
		tag.put("admins", list);
	}

	@Override
	protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
		super.loadAdditional(tag, registries);
		owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
		ownerName = tag.getString("ownerName");
		admins.clear();
		for(Tag element : tag.getList("admins", Tag.TAG_COMPOUND)) {
			CompoundTag entry = (CompoundTag)element;
			if(entry.hasUUID("uuid"))
				admins.put(entry.getUUID("uuid"), entry.getString("name"));
		}
	}

	@Override
	public @NotNull Component getDisplayName() {
		return Component.translatable(Radiocraft.translationKey("container", "moderation_computer"));
	}

	@Override
	public @Nullable AbstractContainerMenu createMenu(int id, @NotNull Inventory inventory, @NotNull Player player) {
		return new ModerationComputerMenu(id, this);
	}
}
