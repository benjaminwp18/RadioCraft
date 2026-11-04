package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.be_networks.network_objects.RadioNetworkObject;
import com.arrl.radiocraft.common.blockentities.DtmfEncoderBlockEntity;
import com.arrl.radiocraft.common.blockentities.PabxBlockEntity;
import com.arrl.radiocraft.common.blockentities.RepeaterAutopatchBlockEntity;
import com.arrl.radiocraft.common.blockentities.TelephoneBlockEntity;
import com.arrl.radiocraft.common.blockentities.VHFRepeaterBlockEntity;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.blockentities.radio.VHFRadioBlockEntity;
import com.arrl.radiocraft.common.blocks.RepeaterAutopatchBlock;
import com.arrl.radiocraft.common.blocks.antennas.DoubleVHFAntennaBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The repeater autopatch (2026-11-04): a station keys the activation code, a number and '*' on the repeater's input
 * and a phone on the PABX rings; '#' hangs up. A call to the autopatch's extension is answered after its ring. During
 * a call the phone's side goes out on the repeater only while nothing comes in on the input. The base stations talk
 * through their bare connectors, a few blocks; each test has its own frequencies.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class AutopatchGameTests {

	private static final String BATCH = "autopatch";
	private static final UUID OPERATOR = UUID.nameUUIDFromBytes("gametest-autopatch-operator".getBytes());

	private static final BlockPos REPEATER = new BlockPos(8, 2, 6); // Faces north, so its back is south (+z).
	private static final BlockPos AUTOPATCH = REPEATER.above();
	private static final BlockPos PABX = AUTOPATCH.above();
	private static final BlockPos PHONE = PABX.north();
	private static final BlockPos STATION_A = new BlockPos(5, 2, 6); // On the input, with an encoder.
	private static final BlockPos STATION_B = new BlockPos(10, 2, 6); // On the output.

	private static RepeaterAutopatchBlockEntity autopatch(GameTestHelper helper) {
		return GameTestUtil.blockEntity(helper, AUTOPATCH, RepeaterAutopatchBlockEntity.class);
	}

	private static TelephoneBlockEntity phone(GameTestHelper helper) {
		return GameTestUtil.blockEntity(helper, PHONE, TelephoneBlockEntity.class);
	}

	private static VHFRepeaterBlockEntity repeater(GameTestHelper helper) {
		return GameTestUtil.blockEntity(helper, REPEATER, VHFRepeaterBlockEntity.class);
	}

	private static DtmfEncoderBlockEntity encoder(GameTestHelper helper) {
		return GameTestUtil.blockEntity(helper, STATION_A.north(), DtmfEncoderBlockEntity.class);
	}

	private static void powerOn(GameTestHelper helper, BlockPos pos) {
		RadioNetworkObject networkObject = GameTestUtil.networkObject(helper, pos, RadioNetworkObject.class);
		if(!networkObject.isPowered)
			GameTestUtil.blockEntity(helper, pos, RadioBlockEntity.class).toggle();
		helper.assertTrue(networkObject.isPowered, "Radio at " + pos.toShortString() + " did not power on");
	}

	/** Repeater with duplexer, coax and J-pole; the autopatch on it, the PABX on that, a phone on the PABX; stations. */
	private static void build(GameTestHelper helper, boolean withRepeater) {
		if(withRepeater) {
			helper.setBlock(REPEATER, RadiocraftBlocks.VHF_REPEATER.get());
			helper.setBlock(REPEATER.south(), RadiocraftBlocks.DUPLEXER.get());
			GameTestUtil.placeWire(helper, REPEATER.south(2), RadiocraftBlocks.COAX_WIRE.get());
			GameTestUtil.placeWire(helper, REPEATER.south(3), RadiocraftBlocks.COAX_WIRE.get());
			helper.setBlock(REPEATER.south(4), RadiocraftBlocks.J_POLE_ANTENNA.get().defaultBlockState().setValue(DoubleVHFAntennaBlock.HALF, DoubleBlockHalf.LOWER));
			helper.setBlock(REPEATER.south(4).above(), RadiocraftBlocks.J_POLE_ANTENNA.get().defaultBlockState().setValue(DoubleVHFAntennaBlock.HALF, DoubleBlockHalf.UPPER));
			helper.setBlock(STATION_A, RadiocraftBlocks.VHF_BASE_STATION.get());
			helper.setBlock(STATION_A.north(), RadiocraftBlocks.DTMF_ENCODER.get());
			helper.setBlock(STATION_B, RadiocraftBlocks.VHF_BASE_STATION.get());
		}
		helper.setBlock(AUTOPATCH, RadiocraftBlocks.REPEATER_AUTOPATCH.get());
		helper.setBlock(PABX, RadiocraftBlocks.PABX.get());
		helper.setBlock(PHONE, RadiocraftBlocks.TELEPHONE.get());
	}

	/** The repeater on {@code inputKHz} / {@code outputKHz}, A on its input, B on its output, all on; the autopatch's code. */
	private static void setUp(GameTestHelper helper, int inputKHz, int outputKHz, String code) {
		CompoundTag settings = new CompoundTag();
		settings.putFloat("rxFrequency", inputKHz * 1000.0F);
		settings.putFloat("txFrequency", outputKHz * 1000.0F);
		settings.putInt("hang", 10);
		repeater(helper).applySettings(settings);
		GameTestUtil.blockEntity(helper, STATION_A, VHFRadioBlockEntity.class).setFrequency(inputKHz * 1000.0F);
		GameTestUtil.blockEntity(helper, STATION_B, VHFRadioBlockEntity.class).setFrequency(outputKHz * 1000.0F);
		powerOn(helper, REPEATER);
		powerOn(helper, STATION_A);
		powerOn(helper, STATION_B);
		helper.assertTrue(autopatch(helper).save("500", code), "500 refused: " + autopatch(helper).getStatusKey());
	}

	private static void ready(GameTestHelper helper, boolean withRepeater) {
		helper.assertTrue(autopatch(helper).isNumbered() && phone(helper).isNumbered()
				&& GameTestUtil.blockEntity(helper, PABX, PabxBlockEntity.class).isPowered(), "not numbered yet");
		if(withRepeater) {
			helper.assertTrue(repeater(helper).hasDuplexer(), "the repeater does not see its duplexer");
			helper.assertTrue(autopatch(helper).getRepeater() == repeater(helper), "the autopatch has not found its repeater");
		}
	}

	private static short[] toneFrame() {
		short[] audio = new short[960];
		for(int i = 0; i < audio.length; i++)
			audio[i] = (short)(Math.sin(2 * Math.PI * 440 * i / 48000.0D) * 8000);
		return audio;
	}

	private static void speak(GameTestHelper helper) {
		VHFRadioBlockEntity radio = GameTestUtil.blockEntity(helper, STATION_A, VHFRadioBlockEntity.class);
		radio.setPTTDown(true);
		radio.acceptVoicePacket(() -> helper.getLevel(), toneFrame(), OPERATOR);
		radio.setPTTDown(false);
	}

	/**
	 * Code, number and '*' on the air ring the phone; once it answers, what A says goes down the line, and the phone's
	 * side goes out on the repeater except while A is talking. '#' on the air hangs up.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 500, batch = BATCH)
	public static void dialsOutTalksAndHangsUp(GameTestHelper helper) {
		build(helper, true);
		long[] at = new long[1];
		helper.startSequence()
				.thenWaitUntil(() -> ready(helper, true))
				.thenExecute(() -> setUp(helper, 146_360, 146_960, "9A"))
				.thenIdle(12) // The repeater looks for a key receiver every 10 ticks.
				.thenExecute(() -> helper.assertTrue(encoder(helper).send("9A" + phone(helper).getExtension() + "*") == 1, "A would not send"))
				.thenWaitUntil(() -> helper.assertTrue(phone(helper).getState() == TelephoneBlockEntity.State.RINGING,
						"the phone is not ringing; autopatch " + autopatch(helper).getState() + " armed " + autopatch(helper).isArmed()
								+ " number " + autopatch(helper).getArmedNumber() + " status " + autopatch(helper).getStatus()))
				.thenWaitUntil(() -> helper.assertTrue(helper.getBlockState(AUTOPATCH).getValue(RepeaterAutopatchBlock.LIT), "the lamp is off while calling"))
				.thenExecute(() -> {
					phone(helper).answer();
					helper.assertTrue(autopatch(helper).getState() == TelephoneBlockEntity.State.IN_CALL, "the autopatch is " + autopatch(helper).getState());
				})
				.thenWaitUntil(() -> helper.assertFalse(repeater(helper).hasTones(), "the connect beep is still waiting"))
				.thenExecute(() -> {
					at[0] = helper.getLevel().getGameTime();
					speak(helper);
					helper.assertTrue(autopatch(helper).getLastRadioGameTime() == at[0], "what A said did not reach the autopatch");
					phone(helper).acceptVoicePacket(() -> helper.getLevel(), toneFrame(), OPERATOR, null);
					helper.assertTrue(repeater(helper).getLastPatchGameTime() < at[0], "the phone went out while A was transmitting");
				})
				.thenIdle(VHFRepeaterBlockEntity.INPUT_GAP_TICKS + 2)
				.thenExecute(() -> {
					helper.assertFalse(repeater(helper).isInputActive(), "A is still coming in");
					phone(helper).acceptVoicePacket(() -> helper.getLevel(), toneFrame(), OPERATOR, null);
					helper.assertTrue(repeater(helper).getLastPatchGameTime() == helper.getLevel().getGameTime(), "the phone did not go out with the input quiet");
				})
				.thenIdle(10)
				.thenExecute(() -> encoder(helper).send("#"))
				.thenWaitUntil(() -> helper.assertTrue(phone(helper).getState() == TelephoneBlockEntity.State.IDLE
						&& autopatch(helper).getState() == TelephoneBlockEntity.State.IDLE, "'#' did not hang up: " + autopatch(helper).getState()))
				.thenWaitUntil(() -> helper.assertFalse(helper.getBlockState(AUTOPATCH).getValue(RepeaterAutopatchBlock.LIT), "the lamp stayed on"))
				.thenWaitUntil(() -> helper.assertFalse(repeater(helper).isTransmitting(), "the repeater is still transmitting")) // Off the air before the next test.
				.thenSucceed();
	}

	/** '#' while armed gives up; a number nobody has fails, and nothing rings. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = BATCH)
	public static void cancelsAndFailsUnknownNumbers(GameTestHelper helper) {
		build(helper, true);
		helper.startSequence()
				.thenWaitUntil(() -> ready(helper, true))
				.thenExecute(() -> setUp(helper, 146_380, 146_980, "7"))
				.thenIdle(12)
				.thenExecute(() -> encoder(helper).send("712"))
				.thenWaitUntil(() -> helper.assertTrue(autopatch(helper).isArmed() && autopatch(helper).getArmedNumber().equals("12"),
						"not armed with 12: " + autopatch(helper).getArmedNumber()))
				.thenExecute(() -> encoder(helper).send("#"))
				.thenWaitUntil(() -> helper.assertFalse(autopatch(helper).isArmed(), "'#' did not cancel"))
				.thenExecute(() -> encoder(helper).send("7999*"))
				.thenWaitUntil(() -> helper.assertTrue(autopatch(helper).getStatus().equals("NUMBER 999 NOT FOUND"), "status " + autopatch(helper).getStatus()))
				.thenExecute(() -> {
					helper.assertTrue(autopatch(helper).getState() == TelephoneBlockEntity.State.IDLE, "the autopatch is " + autopatch(helper).getState());
					helper.assertTrue(phone(helper).getState() == TelephoneBlockEntity.State.IDLE, "the phone is " + phone(helper).getState());
				})
				.thenWaitUntil(() -> helper.assertFalse(repeater(helper).isTransmitting(), "the repeater is still transmitting"))
				.thenSucceed();
	}

	/** A call to its extension rings on the air, then is answered; it ends when the phone hangs up. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300, batch = BATCH)
	public static void answersIncomingAfterItsRing(GameTestHelper helper) {
		build(helper, true);
		helper.startSequence()
				.thenWaitUntil(() -> ready(helper, true))
				.thenExecute(() -> setUp(helper, 146_440, 147_040, "1"))
				.thenExecute(() -> {
					phone(helper).dial("500");
					helper.assertTrue(autopatch(helper).getState() == TelephoneBlockEntity.State.RINGING, "not ringing: " + phone(helper).getStatus());
					helper.assertTrue(repeater(helper).hasTones(), "no ring on the air");
				})
				.thenWaitUntil(() -> helper.assertTrue(phone(helper).getState() == TelephoneBlockEntity.State.IN_CALL, "not answered: " + autopatch(helper).getState()))
				.thenExecute(() -> {
					phone(helper).hangUp();
					helper.assertTrue(autopatch(helper).getState() == TelephoneBlockEntity.State.IDLE, "the autopatch stayed in the call");
				})
				.thenWaitUntil(() -> helper.assertFalse(repeater(helper).isTransmitting(), "the repeater is still transmitting"))
				.thenSucceed();
	}

	/** With no repeater it turns calls away. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = BATCH)
	public static void noRepeaterTurnsCallsAway(GameTestHelper helper) {
		build(helper, false);
		helper.startSequence()
				.thenWaitUntil(() -> ready(helper, false))
				.thenExecute(() -> {
					helper.assertTrue(autopatch(helper).save("500", "1"), "500 refused: " + autopatch(helper).getStatusKey());
					phone(helper).dial("500");
					helper.assertTrue(phone(helper).getState() == TelephoneBlockEntity.State.IDLE, "the phone is " + phone(helper).getState());
					helper.assertTrue(phone(helper).getStatus().equals("CALL REJECTED"), "status " + phone(helper).getStatus());
				})
				.thenSucceed();
	}

	/** Its screen refuses a code with '*' or '#', or too long. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 100, batch = BATCH)
	public static void refusesBadCodes(GameTestHelper helper) {
		build(helper, false);
		helper.startSequence()
				.thenWaitUntil(() -> ready(helper, false))
				.thenExecute(() -> {
					helper.assertFalse(autopatch(helper).save("", "12*"), "a code with * was saved");
					helper.assertTrue(autopatch(helper).getStatusKey().equals("screen.radiocraft.repeater_autopatch.bad_code"), "status " + autopatch(helper).getStatusKey());
					helper.assertFalse(autopatch(helper).save("", "123456789"), "a nine key code was saved");
					helper.assertTrue(autopatch(helper).save("", "12ab"), "12ab refused");
					helper.assertTrue(autopatch(helper).getCode().equals("12AB"), "code " + autopatch(helper).getCode());
				})
				.thenSucceed();
	}
}
