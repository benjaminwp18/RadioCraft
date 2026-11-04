package com.arrl.radiocraft.common.be_networks.network_objects;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.benetworks.PowerNetworkObject;
import com.arrl.radiocraft.common.blockentities.DeskChargerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * The desk charger on the power wire: the network tops up this object's small buffer each tick, and it passes what it
 * got on to the charger's own store, from which the charger charges the handheld in its cradle.
 */
public class DeskChargerNetworkObject extends PowerNetworkObject {

	public static final ResourceLocation TYPE = Radiocraft.id("desk_charger");
	/** Most it takes from the wire a tick: twice what the charger gives a handheld, so a charging one never runs short. */
	public static final int DRAW = 2;

	public DeskChargerNetworkObject(Level level, BlockPos pos) {
		super(level, pos, DRAW, DRAW, DRAW);
	}

	@Override
	public boolean isIndirectConsumer() {
		return true;
	}

	@Override
	public boolean isDirectConsumer() {
		return false;
	}

	@Override
	public void tick(Level level, BlockPos pos) {
		if(!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof DeskChargerBlockEntity charger))
			return;
		int room = charger.energyStorage.receiveEnergy(energyStorage.getEnergyStored(), true);
		if(room > 0)
			charger.energyStorage.receiveEnergy(energyStorage.extractEnergy(room, false), false);
	}

	@Override
	public ResourceLocation getType() {
		return TYPE;
	}
}
