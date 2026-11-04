package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.be_networks.network_objects.RadioNetworkObject;
import com.arrl.radiocraft.common.blockentities.DtmfDecoderBlockEntity;
import com.arrl.radiocraft.common.blockentities.DtmfEncoderBlockEntity;
import com.arrl.radiocraft.common.blockentities.radio.RadioBlockEntity;
import com.arrl.radiocraft.common.blocks.DtmfDecoderBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * DTMF: an encoder against one radio sends touch tones over the air, and a decoder against another finds them in what
 * its radio hears, lights its lamps and drives redstone. The base stations talk through their bare connectors (a few
 * blocks), each test on its own frequency.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class DtmfGameTests {

	private static final BlockPos SENDER = new BlockPos(3, 2, 6);
	private static final BlockPos LISTENER = new BlockPos(6, 2, 6); // 3 blocks: well above the copy threshold.
	private static final BlockPos FAR_LISTENER = new BlockPos(10, 2, 6); // 7 blocks: under the floor, nothing copies.

	private static RadioBlockEntity radio(GameTestHelper helper, BlockPos pos) {
		return GameTestUtil.blockEntity(helper, pos, RadioBlockEntity.class);
	}

	private static DtmfEncoderBlockEntity encoder(GameTestHelper helper) {
		return GameTestUtil.blockEntity(helper, SENDER.north(), DtmfEncoderBlockEntity.class);
	}

	private static DtmfDecoderBlockEntity decoder(GameTestHelper helper, BlockPos radio) {
		return GameTestUtil.blockEntity(helper, radio.north(), DtmfDecoderBlockEntity.class);
	}

	private static BlockState decoderState(GameTestHelper helper, BlockPos radio) {
		return helper.getBlockState(radio.north());
	}

	private static void powerOn(GameTestHelper helper, BlockPos pos) {
		if(!GameTestUtil.networkObject(helper, pos, RadioNetworkObject.class).isPowered)
			radio(helper, pos).toggle();
		helper.assertTrue(GameTestUtil.networkObject(helper, pos, RadioNetworkObject.class).isPowered, "Radio at " + pos.toShortString() + " did not power on");
	}

	/** A VHF base station with an encoder at SENDER, one with a decoder set to {@code code} at {@code listener}. */
	private static void build(GameTestHelper helper, BlockPos listener, int kHz, String code, boolean toggle) {
		helper.setBlock(SENDER, RadiocraftBlocks.VHF_BASE_STATION.get());
		helper.setBlock(SENDER.north(), RadiocraftBlocks.DTMF_ENCODER.get());
		helper.setBlock(listener, RadiocraftBlocks.VHF_BASE_STATION.get());
		helper.setBlock(listener.north(), RadiocraftBlocks.DTMF_DECODER.get());
		radio(helper, SENDER).setFrequency(kHz * 1000.0F);
		radio(helper, listener).setFrequency(kHz * 1000.0F);
		decoder(helper, listener).setCode(code);
		decoder(helper, listener).setToggleMode(toggle);
	}

	/**
	 * The whole code comes in: green lamp, a redstone pulse that ends after a second while the green lamp stays on, and
	 * the green lamp goes out after five seconds.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400)
	public static void codeOverTheAirPulsesAndLightsGreen(GameTestHelper helper) {
		build(helper, LISTENER, 146_110, "12#", false);
		long[] matchedAt = {-1L};
		helper.startSequence()
				.thenExecute(() -> {
					powerOn(helper, SENDER);
					powerOn(helper, LISTENER);
				})
				.thenIdle(12)
				.thenExecute(() -> helper.assertTrue(encoder(helper).send("12#") == 1, "The encoder found no radio to send on"))
				.thenExecute(() -> helper.assertTrue(radio(helper, SENDER).isSendingData(), "The sender's TX lamp is not lit for DTMF"))
				.thenWaitUntil(() -> helper.assertTrue(decoder(helper, LISTENER).getMatches() == 1, "No match yet; heard " + decoder(helper, LISTENER).getHeard()))
				.thenExecute(() -> {
					matchedAt[0] = helper.getLevel().getGameTime();
					BlockState state = decoderState(helper, LISTENER);
					helper.assertTrue(state.getValue(DtmfDecoderBlock.GREEN), "Green lamp is off after a match");
					helper.assertFalse(state.getValue(DtmfDecoderBlock.RED), "Red lamp is still on after the whole code");
					helper.assertTrue(state.getValue(DtmfDecoderBlock.POWERED), "No redstone pulse on a match");
					helper.assertTrue(helper.getLevel().getBestNeighborSignal(helper.absolutePos(LISTENER.north(2))) == 15,
							"The decoder does not power the block beside it");
					helper.assertTrue(decoder(helper, LISTENER).getHeard().equals("12#"), "Heard " + decoder(helper, LISTENER).getHeard());
				})
				.thenIdle(DtmfDecoderBlockEntity.PULSE_TICKS + 2)
				.thenExecute(() -> {
					helper.assertFalse(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.POWERED), "The pulse did not end");
					helper.assertTrue(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.GREEN), "The green lamp went out with the pulse");
				})
				.thenWaitUntil(() -> helper.assertFalse(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.GREEN), "The green lamp stays on"))
				.thenExecute(() -> {
					long lit = helper.getLevel().getGameTime() - matchedAt[0];
					helper.assertTrue(lit >= DtmfDecoderBlockEntity.GREEN_TICKS - 2 && lit <= DtmfDecoderBlockEntity.GREEN_TICKS + 4, "The green lamp was lit for " + lit + " ticks");
				})
				.thenSucceed();
	}

	/** A redstone signal coming on at the encoder sends its last number again; staying on, it sends no more. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 600)
	public static void redstoneResendsLastNumber(GameTestHelper helper) {
		build(helper, LISTENER, 146_610, "47", false);
		helper.startSequence()
				.thenExecute(() -> {
					powerOn(helper, SENDER);
					powerOn(helper, LISTENER);
				})
				.thenIdle(12)
				.thenExecute(() -> helper.assertTrue(encoder(helper).send("47") == 1, "The encoder found no radio to send on"))
				.thenWaitUntil(() -> helper.assertTrue(decoder(helper, LISTENER).getMatches() == 1, "The first send did not match"))
				.thenWaitUntil(() -> helper.assertFalse(radio(helper, SENDER).isSendingData(), "Still sending the first number"))
				.thenExecute(() -> helper.setBlock(SENDER.north(2), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK))
				.thenWaitUntil(() -> helper.assertTrue(decoder(helper, LISTENER).getMatches() == 2, "Redstone did not send the last number again"))
				.thenIdle(60)
				.thenExecute(() -> helper.assertTrue(decoder(helper, LISTENER).getMatches() == 2, "A steady signal kept sending"))
				.thenSucceed();
	}

	/** The start of the code lights the red lamp; with no more keys it goes out once the code times out. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400)
	public static void startOfCodeLightsRedUntilItTimesOut(GameTestHelper helper) {
		build(helper, LISTENER, 146_150, "1234", false);
		helper.startSequence()
				.thenExecute(() -> {
					powerOn(helper, SENDER);
					powerOn(helper, LISTENER);
				})
				.thenIdle(12)
				.thenExecute(() -> encoder(helper).send("12"))
				.thenWaitUntil(() -> helper.assertTrue(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.RED), "The red lamp did not light; heard " + decoder(helper, LISTENER).getHeard()))
				.thenExecute(() -> {
					helper.assertFalse(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.GREEN), "Green lamp on half a code");
					helper.assertFalse(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.POWERED), "Output on half a code");
				})
				.thenWaitUntil(() -> helper.assertTrue(decoder(helper, LISTENER).getHeard().equals("12"), "Heard " + decoder(helper, LISTENER).getHeard()))
				.thenIdle(DtmfDecoderBlockEntity.TIMEOUT_TICKS + 4)
				.thenExecute(() -> helper.assertFalse(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.RED), "The red lamp did not time out"))
				.thenSucceed();
	}

	/** A code that starts wrong still matches once the right keys come (the decoder follows the end of what it hears). */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400)
	public static void wrongStartThenRightCodeMatches(GameTestHelper helper) {
		build(helper, LISTENER, 146_190, "*55", false);
		helper.startSequence()
				.thenExecute(() -> {
					powerOn(helper, SENDER);
					powerOn(helper, LISTENER);
				})
				.thenIdle(12) // The listener looks for a linked decoder every 10 ticks.
				.thenExecute(() -> encoder(helper).send("9*5*55"))
				.thenWaitUntil(() -> helper.assertTrue(decoder(helper, LISTENER).getHeard().equals("9*5*55"), "Heard " + decoder(helper, LISTENER).getHeard()))
				.thenExecute(() -> helper.assertTrue(decoder(helper, LISTENER).getMatches() == 1, "Matched " + decoder(helper, LISTENER).getMatches() + " times"))
				.thenSucceed();
	}

	/** In toggle mode each match flips the output, and it stays put between matches. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 500)
	public static void toggleModeFlipsOnEachMatch(GameTestHelper helper) {
		build(helper, LISTENER, 146_230, "7", true);
		helper.startSequence()
				.thenExecute(() -> {
					powerOn(helper, SENDER);
					powerOn(helper, LISTENER);
				})
				.thenIdle(12)
				.thenExecute(() -> encoder(helper).send("7"))
				.thenWaitUntil(() -> helper.assertTrue(decoder(helper, LISTENER).getMatches() == 1, "No first match"))
				.thenIdle(DtmfDecoderBlockEntity.PULSE_TICKS + 5)
				.thenExecute(() -> helper.assertTrue(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.POWERED), "Toggle mode did not stay on"))
				.thenExecute(() -> encoder(helper).send("7"))
				.thenWaitUntil(() -> helper.assertTrue(decoder(helper, LISTENER).getMatches() == 2, "No second match"))
				.thenExecute(() -> helper.assertFalse(decoderState(helper, LISTENER).getValue(DtmfDecoderBlock.POWERED), "The second match did not turn it off"))
				.thenSucceed();
	}

	/** A signal under the copy floor gets no key through at all, though the tones still reach the radio. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public static void tooWeakCopiesNothing(GameTestHelper helper) {
		build(helper, FAR_LISTENER, 146_270, "123", false);
		helper.startSequence()
				.thenExecute(() -> {
					powerOn(helper, SENDER);
					powerOn(helper, FAR_LISTENER);
				})
				.thenIdle(12)
				.thenExecute(() -> encoder(helper).send("123"))
				.thenWaitUntil(() -> helper.assertFalse(radio(helper, SENDER).isSendingData(), "Still sending"))
				.thenExecute(() -> helper.assertTrue(radio(helper, FAR_LISTENER).getLastSignalGameTime() >= 0, "The far radio heard nothing at all: the test proves nothing"))
				.thenIdle(4)
				.thenExecute(() -> {
					helper.assertTrue(decoder(helper, FAR_LISTENER).getHeard().isEmpty(), "A signal under the floor copied " + decoder(helper, FAR_LISTENER).getHeard());
					helper.assertFalse(decoderState(helper, FAR_LISTENER).getValue(DtmfDecoderBlock.RED), "Red lamp lit on nothing copied");
				})
				.thenSucceed();
	}

	/** A receiver has no transmitter: an encoder on one sends nothing, and says so. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 60)
	public static void receiverCannotSend(GameTestHelper helper) {
		helper.setBlock(SENDER, RadiocraftBlocks.VHF_RECEIVER.get());
		helper.setBlock(SENDER.north(), RadiocraftBlocks.DTMF_ENCODER.get());
		helper.startSequence()
				.thenExecute(() -> powerOn(helper, SENDER))
				.thenIdle(1)
				.thenExecute(() -> {
					helper.assertTrue(encoder(helper).send("123") == 0, "An encoder sent on a receiver");
					helper.assertFalse(radio(helper, SENDER).isSendingData(), "The receiver is sending");
				})
				.thenSucceed();
	}

	/** A radio that is off sends no tones. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 60)
	public static void radioOffSendsNothing(GameTestHelper helper) {
		helper.setBlock(SENDER, RadiocraftBlocks.VHF_BASE_STATION.get());
		helper.setBlock(SENDER.north(), RadiocraftBlocks.DTMF_ENCODER.get());
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(encoder(helper).send("123") == 0, "An encoder sent on a radio that is off");
			helper.assertTrue(encoder(helper).getLastSent().equals("123"), "The encoder forgot the number for REDIAL");
			helper.succeed();
		});
	}
}
