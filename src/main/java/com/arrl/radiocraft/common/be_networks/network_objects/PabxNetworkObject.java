package com.arrl.radiocraft.common.be_networks.network_objects;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.RadiocraftServerConfig;
import com.arrl.radiocraft.common.capabilities.BasicEnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * The phone PABX on the power wire: draws {@code pabx_power_use} a tick, always, as a mains-fed exchange does, and what
 * its phones use on top ({@link #setPhoneLoad}), so they need no wire of their own. Power comes in whole units, so a
 * fraction of one is carried to the next tick. Without enough, the phones it serves have no line (see
 * {@link com.arrl.radiocraft.common.blockentities.PabxBlockEntity}).
 */
public class PabxNetworkObject extends MicrowaveNetworkObjects.StationLink {

	public static final ResourceLocation TYPE = Radiocraft.id("pabx");

	/** Whether the wire fed it this tick. */
	public boolean isPowered = false;
	private double phoneLoad = 0.0D;
	private double carried = 0.0D;
	/** Ticks in a row it came up short: one is forgiven, as the wire only fills a bigger buffer on the next tick. */
	private int shortTicks = 0;

	public PabxNetworkObject(Level level, BlockPos pos) {
		super(level, pos);
		energyStorage = new BasicEnergyStorage(bufferSize(), bufferSize(), bufferSize());
	}

	/** What its phones add per tick; the buffer the wire tops up grows to match. */
	public void setPhoneLoad(double load) {
		phoneLoad = load;
		int size = bufferSize() + (int)Math.ceil(load) + 1;
		if(size != energyStorage.getMaxEnergyStored()) {
			energyStorage.setMaxEnergy(size);
			energyStorage.setMaxReceive(size);
			energyStorage.setMaxExtract(size);
			energyStorage.setEnergy(energyStorage.getEnergyStored());
		}
	}

	/** One tick's draw: what the power network tops up each tick. */
	private static int bufferSize() {
		try {
			return Math.max(1, RadiocraftServerConfig.PABX_POWER_USE.get());
		}
		catch(IllegalStateException e) { // Config not loaded yet.
			return 2;
		}
	}

	@Override
	public boolean isIndirectConsumer() {
		return true;
	}

	@Override
	public void tick(Level level, BlockPos pos) {
		if(!RadiocraftServerConfig.RADIO_POWER_REQUIRED.get()) {
			isPowered = true;
			return;
		}
		double due = carried + phoneLoad;
		int whole = (int)due;
		if(tryConsumeEnergy(RadiocraftServerConfig.PABX_POWER_USE.get() + whole, false)) {
			carried = due - whole;
			shortTicks = 0;
			isPowered = true;
		}
		else
			isPowered = ++shortTicks < 2 && isPowered;
	}

	@Override
	public ResourceLocation getType() {
		return TYPE;
	}
}
