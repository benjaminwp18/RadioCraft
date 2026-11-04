package com.arrl.radiocraft.common.blockentities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import com.arrl.radiocraft.common.init.RadiocraftBlockEntities;
import com.arrl.radiocraft.Radiocraft;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;
import com.arrl.radiocraft.common.capabilities.BasicEnergyStorage;
import com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability;
import com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities;
import com.arrl.radiocraft.common.items.VHFHandheldItem;
import com.arrl.radiocraft.common.radio.voice.handheld.DockedHandheld;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * The desk charger: charges the battery of what is in its cradle from its own store, which it fills from a power wire
 * ({@link com.arrl.radiocraft.common.be_networks.network_objects.DeskChargerNetworkObject}). A VHF handheld in it stays a
 * working radio ({@link DockedHandheld}): switched on, it uses its battery as in the hand (so it runs down on a charger
 * with no power), hears its frequency through its speaker, and its APRS module beacons and takes packets. Its screen
 * lights while it hears something.
 */
public class DeskChargerBlockEntity extends BlockEntity implements GeoBlockEntity, MenuProvider, com.arrl.radiocraft.api.benetworks.INetworkObjectProvider {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    public final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        protected int getStackLimit(int slot, ItemStack stack) {
            return 64;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    // Block-level energy buffer used to charge radios placed on the desk charger
    public final BasicEnergyStorage energyStorage = new BasicEnergyStorage(1000, 250, 250);
    // When true the charger will supply energy to radios without draining its internal buffer
    private boolean infinite = false;

    // The handheld in the cradle as a radio (server only, while one is there) and whether it is hearing something (synced).
    @Nullable
    private DockedHandheld docked = null;
    private boolean receiving = false;

    public DeskChargerBlockEntity(BlockPos pos, BlockState state) {
        super(RadiocraftBlockEntities.DESK_CHARGER.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide)
            getNetworkObject(level, worldPosition); // Joins a power wire touching it.
    }

    @Override
    public com.arrl.radiocraft.api.benetworks.BENetworkObject createNetworkObject() {
        return new com.arrl.radiocraft.common.be_networks.network_objects.DeskChargerNetworkObject(level, worldPosition);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        undock();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        undock();
    }

    private void undock() {
        if (docked != null) {
            docked.close();
            docked = null;
        }
    }

    public boolean hasRadio() {
        return !inventory.getStackInSlot(0).isEmpty();
    }

    /** Puts the handheld in {@code player}'s {@code hand} in the cradle; one already there goes into that hand. */
    public void dock(Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof VHFHandheldItem))
            return;
        ItemStack old = inventory.getStackInSlot(0).copy();
        inventory.setStackInSlot(0, held.copyWithCount(1));
        held.shrink(1);
        if (held.isEmpty())
            player.setItemInHand(hand, old);
        else if (!old.isEmpty() && !player.getInventory().add(old))
            player.drop(old, false);
        click();
    }

    /** Hands the radio in the cradle to {@code player}: into their main hand if it is empty, else their inventory (or at their feet). */
    public void takeRadio(Player player) {
        ItemStack radio = inventory.getStackInSlot(0).copy();
        if (radio.isEmpty())
            return;
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        if (player.getMainHandItem().isEmpty())
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, radio);
        else if (!player.getInventory().add(radio))
            player.drop(radio, false);
        click();
    }

    private void click() {
        if (level != null)
            level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.ITEM_FRAME_ADD_ITEM, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.2F);
        sync();
    }

    /** True while the handheld in it hears a signal (its screen lights). Synced to clients. */
    public boolean isReceiving() {
        return receiving;
    }

    /** Sends the charger, and the handheld in it, to clients watching. */
    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    /**
     * The handheld in the cradle runs as a radio: its battery drains as in the hand, it goes off when flat, and its
     * receiver and APRS module work through {@link DockedHandheld}.
     */
    private void tickHandheld(ServerLevel level) {
        ItemStack radio = inventory.getStackInSlot(0);
        if (!(radio.getItem() instanceof VHFHandheldItem)) {
            undock();
            setReceiving(false);
            return;
        }
        IVHFHandheldCapability cap = radio.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
        net.neoforged.neoforge.energy.IEnergyStorage energy = radio.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (cap != null && cap.isPowered() && energy != null) {
            if (cap.isPTTDown())
                cap.setPTTDown(false); // Nobody is holding it to talk.
            int drain = VHFHandheldItem.calculateEnergyConsumption(cap, level.getGameTime());
            if (energy.getEnergyStored() <= 0) {
                cap.setPowered(false); // Flat: it switches itself off.
                sync();
            } else if (drain > 0) {
                energy.extractEnergy(drain, false);
            }
        }
        if (docked == null)
            docked = new DockedHandheld(level, worldPosition, this::sync);
        docked.tick(radio);
        setReceiving(docked.isReceiving());
    }

    private void setReceiving(boolean now) {
        if (now != receiving) {
            receiving = now;
            sync();
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, DeskChargerBlockEntity be) {
        if (level.isClientSide()) return;
        be.tickHandheld((ServerLevel) level);

        // Get block energy capability
        // Use the BE's own energyStorage for reliable transfers
        net.neoforged.neoforge.energy.IEnergyStorage blockEnergy = be.energyStorage;
        if (blockEnergy == null) return;

        // Get the radio in slot 0
        ItemStack radio = be.inventory.getStackInSlot(0);
        if (radio == null || radio.isEmpty()) return;

        // Ensure item has energy capability
        net.neoforged.neoforge.energy.IEnergyStorage radioEnergy = radio.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        if (radioEnergy == null) return;

        // Transfer rate per tick
        int desiredTransfer = 1; // FE per tick: a flat small battery's worth in about 50 minutes (maintainer, 2026-11-04: an eighth of the old 8)

        // Don't extract before knowing how much radio can accept. Simulate receive first.
        int available = blockEnergy.getEnergyStored();
        if (available <= 0) return;

        int toAttempt = Math.min(desiredTransfer, available);
        // If infinite mode is enabled, simulate acceptance up to desiredTransfer but don't extract from buffer
            if (be.infinite) {
            int accepted = radioEnergy.receiveEnergy(desiredTransfer, true);
            if (accepted <= 0) return;
            int received = radioEnergy.receiveEnergy(accepted, false);
            // log what was delivered
            Radiocraft.LOGGER.debug("DeskCharger tick (infinite) @ {}: delivered={} to radio", pos, received);
                be.setChanged();
                // notify clients so render layer updates without opening GUI
                level.sendBlockUpdated(pos, state, state, 3);
            return;
        }

        int accepted = radioEnergy.receiveEnergy(toAttempt, true); // simulate
        if (accepted <= 0) return;

        int extracted = blockEnergy.extractEnergy(accepted, false);
        if (extracted <= 0) return;

        int received = radioEnergy.receiveEnergy(extracted, false);
        if (received < extracted) {
            // return remainder to block
            blockEnergy.receiveEnergy(extracted - received, false);
        }

        // Debug logging to help trace transfer behavior
        int radioStoredAfter = radioEnergy.getEnergyStored();
        Radiocraft.LOGGER.debug("DeskCharger tick @ {}: available={}, toAttempt={}, accepted(sim)={}, extracted={}, received={}, radioStoredAfter={}", pos, available, toAttempt, accepted, extracted, received, radioStoredAfter);

        be.setChanged();
        // notify clients so render layer updates without opening GUI
        level.sendBlockUpdated(pos, state, state, 3);
    }

    public void drops() {
        SimpleContainer inv = new SimpleContainer(inventory.getSlots());
        for (int i = 0; i < inventory.getSlots(); i++) {
            inv.setItem(i, inventory.getStackInSlot(i));
        }
        Containers.dropContents(this.level, this.worldPosition, inv);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // No animations for now; placeholder for future controllers
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void saveAdditional(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        CompoundTag energyTag = new CompoundTag();
        energyStorage.saveAdditional(energyTag);
        tag.put("energy", energyTag);
        tag.putBoolean("infinite", this.infinite);
        tag.putBoolean("receiving", this.receiving);
    }

    @Override
    public void loadAdditional(net.minecraft.nbt.CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("inventory")) {
            inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        }
        if (tag.contains("energy")) {
            CompoundTag energyTag = tag.getCompound("energy");
            if (energyTag.contains("capacity")) {
                energyStorage.setMaxEnergy(energyTag.getInt("capacity"));
            }
            if (energyTag.contains("maxReceive")) {
                energyStorage.setMaxReceive(energyTag.getInt("maxReceive"));
            }
            if (energyTag.contains("maxExtract")) {
                energyStorage.setMaxExtract(energyTag.getInt("maxExtract"));
            }
            if (energyTag.contains("energy")) {
                energyStorage.setEnergy(energyTag.getInt("energy"));
            }
        }
        if (tag.contains("infinite")) {
            this.infinite = tag.getBoolean("infinite");
        }
        this.receiving = tag.getBoolean("receiving");
    }

    public boolean isInfinite() {
        return this.infinite;
    }

    public void setInfinite(boolean value) {
        this.infinite = value;
        this.setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(Radiocraft.translationKey("screen", "desk_charger"));
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new com.arrl.radiocraft.common.menus.DeskChargerMenu(id, inventory, this);
    }
}
