package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability;
import com.arrl.radiocraft.common.be_networks.network_objects.DeskChargerNetworkObject;
import com.arrl.radiocraft.common.be_networks.network_objects.RadioNetworkObject;
import com.arrl.radiocraft.common.blockentities.AprsTerminalBlockEntity;
import com.arrl.radiocraft.common.blockentities.DeskChargerBlockEntity;
import com.arrl.radiocraft.common.blockentities.RadioInterfaceBlockEntity;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.arrl.radiocraft.common.radio.voice.handheld.HandheldTnc;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A VHF handheld in a desk charger is still a radio: switched on, it runs its battery down on a charger without power
 * (and switches off when it is flat), and its APRS module hears beacons from the cradle. The charger joins power wire.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class DeskChargerGameTests {

	private static final BlockPos CHARGER = new BlockPos(6, 2, 6);
	private static final BlockPos RADIO = new BlockPos(3, 2, 6);

	private static DeskChargerBlockEntity charger(GameTestHelper helper) {
		return GameTestUtil.blockEntity(helper, CHARGER, DeskChargerBlockEntity.class);
	}

	/** A switched-on handheld holding {@code energy}, with an APRS module if {@code aprs}. */
	private static ItemStack handheld(int energy, boolean aprs) {
		ItemStack stack = new ItemStack(RadiocraftItems.VHF_HANDHELD.get());
		if(aprs)
			stack.set(RadiocraftDataComponent.HANDHELD_APRS.value(), HandheldAprs.INSTALLED);
		IVHFHandheldCapability cap = stack.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
		cap.setPowered(true);
		IEnergyStorage battery = stack.getCapability(Capabilities.EnergyStorage.ITEM);
		battery.extractEnergy(battery.getEnergyStored(), false);
		battery.receiveEnergy(energy, false);
		return stack;
	}

	private static ItemStack docked(GameTestHelper helper) {
		return charger(helper).inventory.getStackInSlot(0);
	}

	/** On a charger with nothing in its store, a switched-on handheld drains, and switches off once flat. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = "desk_charger")
	public static void handheldDrainsOnUnpoweredCharger(GameTestHelper helper) {
		helper.setBlock(CHARGER, RadiocraftBlocks.DESK_CHARGER.get());
		int[] start = {0};
		helper.startSequence()
				.thenExecute(() -> {
					charger(helper).energyStorage.setEnergy(0);
					charger(helper).inventory.setStackInSlot(0, handheld(3, false));
					start[0] = docked(helper).getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored();
				})
				.thenWaitUntil(() -> helper.assertTrue(docked(helper).getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored() < start[0],
						"The handheld in the charger is not using its battery"))
				.thenWaitUntil(() -> helper.assertFalse(docked(helper).getCapability(RadiocraftCapabilities.VHF_HANDHELDS).isPowered(),
						"The flat handheld did not switch off"))
				.thenSucceed();
	}

	/** Sneak-use with a handheld docks it (swapping with one there); sneaking with an empty hand takes it back. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "desk_charger")
	public static void dockAndTakeByHand(GameTestHelper helper) {
		helper.setBlock(CHARGER, RadiocraftBlocks.DESK_CHARGER.get());
		net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
		helper.startSequence()
				.thenExecute(() -> {
					ItemStack first = new ItemStack(RadiocraftItems.VHF_HANDHELD.get());
					player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, first);
					charger(helper).dock(player, net.minecraft.world.InteractionHand.MAIN_HAND);
					helper.assertTrue(docked(helper).is(RadiocraftItems.VHF_HANDHELD.get()), "The handheld did not go into the cradle");
					helper.assertTrue(player.getMainHandItem().isEmpty(), "The hand still holds it");
					net.minecraft.world.item.Item pure = RadiocraftItems.VHF_HANDHELD_VARIANTS.get(com.arrl.radiocraft.common.radio.crystal.CrystalQuality.PURE).get();
					player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(pure));
					charger(helper).dock(player, net.minecraft.world.InteractionHand.MAIN_HAND);
					helper.assertTrue(docked(helper).is(pure), "The second handheld did not swap in");
					helper.assertTrue(player.getMainHandItem().is(RadiocraftItems.VHF_HANDHELD.get()), "The first handheld did not come back to the hand");
					player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
					charger(helper).takeRadio(player);
					helper.assertTrue(docked(helper).isEmpty(), "The cradle is not empty after taking");
					helper.assertTrue(player.getMainHandItem().is(pure), "The radio did not go to the hand");
				})
				.thenSucceed();
	}

	/** The charger has a power network object, so power wire joins it. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "desk_charger")
	public static void chargerJoinsPowerWire(GameTestHelper helper) {
		helper.setBlock(CHARGER, RadiocraftBlocks.DESK_CHARGER.get());
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> GameTestUtil.networkObject(helper, CHARGER, DeskChargerNetworkObject.class))
				.thenSucceed();
	}

	/** An H19 station's beacon on the APRS frequency lands on the station list of a handheld standing in a charger. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = "desk_charger_aprs")
	public static void dockedHandheldHearsBeacon(GameTestHelper helper) {
		helper.setBlock(RADIO, RadiocraftBlocks.VHF_BASE_STATION.get());
		helper.setBlock(RADIO.north(), RadiocraftBlocks.RADIO_INTERFACE.get());
		helper.setBlock(RADIO.north(2), RadiocraftBlocks.APRS_TERMINAL.get());
		helper.setBlock(CHARGER, RadiocraftBlocks.DESK_CHARGER.get());
		RadioBlockEntity radio = GameTestUtil.blockEntity(helper, RADIO, RadioBlockEntity.class);
		radio.setFrequency(HandheldTnc.frequency());
		GameTestUtil.blockEntity(helper, RADIO.north(), RadioInterfaceBlockEntity.class).setCallsign("W1DOK", "gametest");
		helper.startSequence()
				.thenExecute(() -> {
					charger(helper).inventory.setStackInSlot(0, handheld(10_000, true));
					if(!GameTestUtil.networkObject(helper, RADIO, RadioNetworkObject.class).isPowered)
						radio.toggle();
				})
				.thenIdle(2)
				.thenExecute(() -> helper.assertTrue(GameTestUtil.blockEntity(helper, RADIO.north(2), AprsTerminalBlockEntity.class).beacon(), "The H19 did not beacon"))
				.thenWaitUntil(() -> {
					HandheldAprs aprs = docked(helper).get(RadiocraftDataComponent.HANDHELD_APRS.value());
					helper.assertTrue(aprs != null && aprs.stations().stream().anyMatch(station -> station.callsign().equals("W1DOK")), "The docked handheld never heard W1DOK");
				})
				.thenExecute(() -> charger(helper).inventory.setStackInSlot(0, ItemStack.EMPTY)) // Off the air again.
				.thenSucceed();
	}
}
