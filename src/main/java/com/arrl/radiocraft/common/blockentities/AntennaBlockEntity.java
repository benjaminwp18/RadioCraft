package com.arrl.radiocraft.common.blockentities;

import com.arrl.radiocraft.RadiocraftConfig;
import com.arrl.radiocraft.api.antenna.IAntennaType;
import com.arrl.radiocraft.api.benetworks.IBENetworkItem;
import com.arrl.radiocraft.common.benetworks.BENetwork;
import com.arrl.radiocraft.common.benetworks.BENetwork.BENetworkEntry;
import com.arrl.radiocraft.common.power.PowerNetwork;
import com.arrl.radiocraft.common.radio.AntennaManager;
import com.arrl.radiocraft.common.radio.AntennaNetwork;
import com.arrl.radiocraft.common.radio.antenna.Antenna;
import com.arrl.radiocraft.common.radio.antenna.AntennaTypes;
import com.arrl.radiocraft.common.radio.voice.AntennaNetworkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared BlockEntity for all blocks which act as an antenna-- used for processing packets/sending them to the network, receiving packets from the network & scheduling antenna update checks.
 */
public class AntennaBlockEntity extends BlockEntity implements IBENetworkItem {

	private final Map<Direction, Set<BENetwork>> networks = new HashMap<>();
	private Antenna<?> antenna = null;

	// Cache the results of antenna/radio updates and only update them at delays, cutting down on resource usage. Keep BENetworkEntry to ensure that it uses weak refs.
	private final Set<BENetworkEntry> radios = new HashSet<>();
	private int antennaCheckCooldown = -1;

	public AntennaBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public void transmitAudioPacket(short[] rawAudio, int wavelength, int frequency) {
		antenna.transmitAudioPacket(rawAudio, wavelength, frequency);
	}

	public void receiveAudioPacket(AntennaNetworkPacket packet) {

	}

	/**
	 * Updates the antenna at this position in the world
	 */
	private void updateAntenna() {
		AntennaNetwork network = AntennaManager.getNetwork(level);
		antenna = AntennaTypes.match(level, worldPosition);
		if(antenna != null) {
			network.addAntenna(worldPosition, antenna);
			antenna.setNetwork(network);
		}
		else
			network.removeAntenna(worldPosition);
	}

	private void updateConnectedRadios() {
		radios.clear();
		for(Set<BENetwork> side : networks.values()) {
			for(BENetwork network : side) {
				if(!(network instanceof PowerNetwork)) {
					for(BENetworkEntry entry : network.getConnections()) {
						if(entry.getNetworkItem() instanceof AbstractRadioBlockEntity)
							radios.add(entry);
					}
				}
			}
		}
	}

	/**
	 * Reset the antenna check cooldown-- used every time a block is placed "on" the antenna.
	 */
	public void markAntennaChanged() {
		antennaCheckCooldown = RadiocraftConfig.ANTENNA_UPDATE_DELAY.get();
	}

	public static <T extends BlockEntity> void tick(Level level, BlockPos blockPos, BlockState blockState, T t) {
		if(t instanceof AntennaBlockEntity be) {
			if(!level.isClientSide) { // Serverside only
				if(be.antennaCheckCooldown-- == 0)
					be.updateAntenna();
			}
		}
	}

	@Override
	protected void saveAdditional(CompoundTag nbt) {
		super.saveAdditional(nbt);
		if(antenna != null) {
			nbt.putString("antennaType", antenna.type.getId().toString());
			nbt.put("antennaData", antenna.serializeNBT());
		}
	}

	@Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
		if(nbt.contains("antennaType")) {
			IAntennaType<?> type = AntennaTypes.getType(new ResourceLocation(nbt.getString("type")));
			if(type != null) {
				antenna = new Antenna<>(type, worldPosition);
				antenna.deserializeNBT(nbt.getCompound("antennaData"));
			}
		}
	}

	@Override
	public void onLoad() {
		if(antenna != null) { // Handle network set here where level is not null
			AntennaNetwork network = AntennaManager.getNetwork(level);
			network.addAntenna(worldPosition, antenna);
			antenna.setNetwork(network);
		}
	}

	@Override
	public Map<Direction, Set<BENetwork>> getNetworkMap() {
		return networks;
	}

	@Override
	public void networkUpdated(BENetwork network) {
		updateConnectedRadios();
	}
}
