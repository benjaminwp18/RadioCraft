package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.api.capabilities.IVHFHandheldCapability;
import com.arrl.radiocraft.common.be_networks.network_objects.RadioNetworkObject;
import com.arrl.radiocraft.common.blockentities.AprsTerminalBlockEntity;
import com.arrl.radiocraft.common.blockentities.RadioInterfaceBlockEntity;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.capabilities.RadiocraftCapabilities;
import com.arrl.radiocraft.common.datacomponents.HandheldAprs;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.init.RadiocraftDataComponent;
import com.arrl.radiocraft.common.init.RadiocraftItems;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.antenna.AntennaVoicePacket;
import com.arrl.radiocraft.common.radio.data.HandheldAprsStation;
import com.arrl.radiocraft.common.radio.data.TncPacket;
import com.arrl.radiocraft.common.radio.voice.handheld.PlayerRadio;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The APRS module in a VHF handheld: what it does with packets (logged, acked, ignored), its beacon timer, a terminal's
 * beacon reaching a handheld on the APRS frequency; and a transceiver that hears nothing while it transmits.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class HandheldAprsGameTests {

	private static final LongSupplier IDS = new LongSupplier() {
		private long next = 1000L;

		@Override
		public long getAsLong() {
			return next++;
		}
	};

	// -------------------- THE MODULE'S LOGIC --------------------

	/** A station's second beacon replaces its first, with its new position and time. */
	@GameTest(template = GameTestUtil.EMPTY)
	public static void beaconGoesOnTheStationListOncePerStation(GameTestHelper helper) {
		List<TncPacket> out = new ArrayList<>();
		HandheldAprs aprs = HandheldAprsStation.receive(HandheldAprs.INSTALLED, TncPacket.beacon(1L, "W1AW", new BlockPos(10, 64, -20), "HI", "HOUSE"), "K1ABC", 5L, out, IDS);
		aprs = HandheldAprsStation.receive(aprs, TncPacket.beacon(2L, "W1AW", new BlockPos(30, 64, -20), "", "CAR"), "K1ABC", 9L, out, IDS);
		helper.assertTrue(aprs.stations().size() == 1, "W1AW is listed " + aprs.stations().size() + " times");
		helper.assertTrue(aprs.stations().getFirst().pos().equals(new BlockPos(30, 64, -20)), "The station kept its old position");
		helper.assertTrue(aprs.stations().getFirst().heardAt() == 9L, "The station kept its old time");
		helper.assertTrue(out.isEmpty(), "A beacon was answered");
		helper.succeed();
	}

	/** A message for us is logged unread and acked, and acked again (but not logged again) when it comes back as a retry. */
	@GameTest(template = GameTestUtil.EMPTY)
	public static void messageForUsIsLoggedAndAcked(GameTestHelper helper) {
		List<TncPacket> out = new ArrayList<>();
		TncPacket message = TncPacket.message(3L, "W1AW", "K1ABC", "HELLO", 7);
		HandheldAprs aprs = HandheldAprsStation.receive(HandheldAprs.INSTALLED, message, "K1ABC", 5L, out, IDS);
		helper.assertTrue(aprs.messages().size() == 1 && aprs.hasUnread(), "The message was not logged unread");
		helper.assertTrue("HELLO".equals(aprs.messages().getFirst().text()), "The message reads " + aprs.messages().getFirst().text());
		helper.assertTrue(out.size() == 1 && out.getFirst().kind() == TncPacket.Kind.ACK && "W1AW".equals(out.getFirst().to()) && "7".equals(out.getFirst().text()),
				"No ack of message 7 went back to W1AW");
		HandheldAprs again = HandheldAprsStation.receive(aprs, message.withId(4L), "K1ABC", 6L, out, IDS);
		helper.assertTrue(again.messages().size() == 1, "The retry was logged twice");
		helper.assertTrue(out.size() == 2, "The retry was not acked");
		helper.succeed();
	}

	/** Messages for other stations are not ours; a bulletin to ALL is kept and wants no ack. */
	@GameTest(template = GameTestUtil.EMPTY)
	public static void othersMessagesIgnoredBulletinsKept(GameTestHelper helper) {
		List<TncPacket> out = new ArrayList<>();
		HandheldAprs aprs = HandheldAprsStation.receive(HandheldAprs.INSTALLED, TncPacket.message(5L, "W1AW", "N0CALL", "NOT YOURS", 1), "K1ABC", 1L, out, IDS);
		helper.assertTrue(aprs.messages().isEmpty(), "A message for N0CALL was kept");
		aprs = HandheldAprsStation.receive(aprs, TncPacket.message(6L, "W1AW", "ALL", "NET AT 8"), "K1ABC", 1L, out, IDS);
		helper.assertTrue(aprs.messages().size() == 1, "The bulletin was not kept");
		helper.assertTrue(out.isEmpty(), "A bulletin was acked");
		helper.succeed();
	}

	/** A message we send goes out with the next number and shows ACK when its ack comes back. */
	@GameTest(template = GameTestUtil.EMPTY)
	public static void sentMessageIsAckedByItsNumber(GameTestHelper helper) {
		List<TncPacket> out = new ArrayList<>();
		HandheldAprs aprs = HandheldAprsStation.send(HandheldAprs.INSTALLED, "K1ABC", "W1AW", "73", 1L, out, IDS);
		helper.assertTrue(out.size() == 1 && "1".equals(out.getFirst().messageNumber()), "The message did not go out as number 1");
		helper.assertTrue(aprs.nextNumber() == 2, "The next number is " + aprs.nextNumber());
		helper.assertTrue(aprs.messages().getFirst().status() == HandheldAprs.Status.SENT, "The message is not SENT");
		aprs = HandheldAprsStation.receive(aprs, TncPacket.ack(9L, "W1AW", "K1ABC", "1"), "K1ABC", 2L, out, IDS);
		helper.assertTrue(aprs.messages().getFirst().status() == HandheldAprs.Status.ACKED, "The ack did not mark the message");
		helper.succeed();
	}

	/** Our own beacon coming back off a digipeater is not a station heard. */
	@GameTest(template = GameTestUtil.EMPTY)
	public static void ownPacketsComingBackAreIgnored(GameTestHelper helper) {
		HandheldAprs aprs = HandheldAprsStation.receive(HandheldAprs.INSTALLED, TncPacket.beacon(1L, "K1ABC", BlockPos.ZERO, "", "PORTABLE"), "K1ABC", 1L, new ArrayList<>(), IDS);
		helper.assertTrue(aprs.stations().isEmpty(), "Our own beacon was listed");
		helper.succeed();
	}

	/** With the beacon on it goes right away, then once each interval, walking (PORTABLE); off, never. */
	@GameTest(template = GameTestUtil.EMPTY)
	public static void beaconTimer(GameTestHelper helper) {
		HandheldAprs on = HandheldAprs.INSTALLED.withBeacon(true);
		helper.assertTrue(HandheldAprsStation.beaconDue(on, 100L, 2400L), "Switching the beacon on did not beacon right away");
		List<TncPacket> out = new ArrayList<>();
		HandheldAprs sent = HandheldAprsStation.beacon(on, "K1ABC", new BlockPos(1, 2, 3), 100L, out, IDS);
		helper.assertFalse(HandheldAprsStation.beaconDue(sent, 200L, 2400L), "It beaconed again too soon");
		helper.assertTrue(HandheldAprsStation.beaconDue(sent, 2500L, 2400L), "It did not beacon after the interval");
		helper.assertFalse(HandheldAprsStation.beaconDue(HandheldAprs.INSTALLED, 100L, 2400L), "It beacons with the beacon off");
		helper.assertTrue(out.getFirst().kind() == TncPacket.Kind.BEACON && "PORTABLE".equals(out.getFirst().icon()), "The beacon is not a walking station");
		helper.succeed();
	}

	// -------------------- OVER THE AIR --------------------

	private static final BlockPos RADIO = new BlockPos(3, 2, 6);

	/** A switched on handheld with an APRS module in the first slot of a mock player standing at {@code at}. */
	private static Player holder(GameTestHelper helper, BlockPos at) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.setPos(Vec3.atBottomCenterOf(helper.absolutePos(at)));
		ItemStack handheld = new ItemStack(RadiocraftItems.VHF_HANDHELD.get());
		handheld.set(RadiocraftDataComponent.HANDHELD_APRS.value(), HandheldAprs.INSTALLED);
		IVHFHandheldCapability cap = handheld.getCapability(RadiocraftCapabilities.VHF_HANDHELDS);
		cap.setPowered(true);
		cap.setFrequencyHertz(146_520_000.0F); // The module listens on the APRS frequency whatever the dial says.
		player.getInventory().setItem(0, handheld);
		player.getInventory().selected = 8;
		return player;
	}

	/** An H19 station's beacon on 144.390 lands on the station list of a handheld's APRS module a few blocks away. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = "handheld_aprs")
	public static void terminalBeaconReachesHandheld(GameTestHelper helper) {
		helper.setBlock(RADIO, RadiocraftBlocks.VHF_BASE_STATION.get());
		helper.setBlock(RADIO.north(), RadiocraftBlocks.RADIO_INTERFACE.get());
		helper.setBlock(RADIO.north(2), RadiocraftBlocks.APRS_TERMINAL.get());
		RadioBlockEntity radio = GameTestUtil.blockEntity(helper, RADIO, RadioBlockEntity.class);
		radio.setFrequency(PlayerRadio.aprsFrequency());
		GameTestUtil.blockEntity(helper, RADIO.north(), RadioInterfaceBlockEntity.class).setCallsign("W1HHT", "gametest");
		Player player = holder(helper, RADIO.east(4));
		PlayerRadio handheld = new PlayerRadio(player);
		helper.startSequence()
				.thenExecute(() -> {
					if(!GameTestUtil.networkObject(helper, RADIO, RadioNetworkObject.class).isPowered)
						radio.toggle();
					handheld.tick();
				})
				.thenIdle(1)
				.thenExecute(() -> {
					handheld.tick();
					helper.assertTrue(GameTestUtil.blockEntity(helper, RADIO.north(2), AprsTerminalBlockEntity.class).beacon(), "The H19 did not beacon");
				})
				.thenWaitUntil(() -> {
					handheld.tick();
					HandheldAprs aprs = player.getInventory().getItem(0).get(RadiocraftDataComponent.HANDHELD_APRS.value());
					helper.assertTrue(aprs != null && aprs.stations().stream().anyMatch(station -> station.callsign().equals("W1HHT")), "The handheld never heard W1HHT");
				})
				.thenExecute(() -> handheld.setPlayer(null))
				.thenSucceed();
	}

	/** A base station hears a signal on its frequency, but not while its own PTT is down. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = "deaf_transmitting")
	public static void transceiverIsDeafWhileTransmitting(GameTestHelper helper) {
		helper.setBlock(RADIO, RadiocraftBlocks.VHF_BASE_STATION.get());
		RadioBlockEntity radio = GameTestUtil.blockEntity(helper, RADIO, RadioBlockEntity.class);
		radio.setFrequency(147_010_000.0F);
		helper.startSequence()
				.thenExecute(() -> {
					if(!GameTestUtil.networkObject(helper, RADIO, RadioNetworkObject.class).isPowered)
						radio.toggle();
				})
				.thenWaitUntil(() -> helper.assertTrue(radio.getBareConnector() != null, "No bare connector yet"))
				.thenExecute(() -> {
					AntennaVoicePacket packet = new AntennaVoicePacket(() -> helper.getLevel(), new short[960], Band.getBand("2m"), 147_010_000.0F, 1.0D, radio.getBareConnector(), UUID.randomUUID());
					helper.assertTrue(radio.receiveVoice(packet), "The radio did not hear a signal on its frequency");
					radio.setPTTDown(true);
					helper.assertTrue(radio.isDeafNow(), "Keyed up, the radio does not count as transmitting");
					helper.assertFalse(radio.receiveVoice(packet), "The radio heard a signal while transmitting");
					radio.setPTTDown(false);
				})
				.thenSucceed();
	}
}
