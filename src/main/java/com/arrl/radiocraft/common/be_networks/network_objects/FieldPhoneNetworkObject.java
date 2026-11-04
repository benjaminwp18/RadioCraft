package com.arrl.radiocraft.common.be_networks.network_objects;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.api.benetworks.PowerBENetwork;
import com.arrl.radiocraft.api.benetworks.PowerNetworkObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * A field phone on the power wire. It needs none (its own batteries ring and talk), but with power it draws
 * {@code phone_power_use} a tick and its party line is louder and takes more phones
 * ({@link com.arrl.radiocraft.common.blockentities.FieldPhoneBlockEntity}). A fraction of a unit is carried to the
 * next tick. With radios not needing power, being on a power wire is enough.
 */
public class FieldPhoneNetworkObject extends PowerNetworkObject {

	public static final ResourceLocation TYPE = Radiocraft.id("field_phone");

	/** Whether the wire fed it this tick. */
	public boolean isPowered = false;
	private double carried = 0.0D;

	public FieldPhoneNetworkObject(Level level, BlockPos pos) {
		super(level, pos, 2, 2, 2);
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
		boolean wired = networks.values().stream().anyMatch(network -> network instanceof PowerBENetwork);
		if(!wired) {
			isPowered = false;
			return;
		}
		if(!RadiocraftServerConfig.RADIO_POWER_REQUIRED.get()) {
			isPowered = true;
			return;
		}
		double due = carried + RadiocraftServerConfig.PHONE_POWER_USE.get();
		int whole = (int)due;
		if(whole == 0) { // Nothing whole to draw this tick: powered if there is any to draw next.
			carried = due;
			isPowered = tryConsumeEnergy(1, true);
		}
		else if(tryConsumeEnergy(whole, false)) {
			carried = due - whole;
			isPowered = true;
		}
		else
			isPowered = false;
	}

	@Override
	public ResourceLocation getType() {
		return TYPE;
	}
}
