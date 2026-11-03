package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.blockentities.PabxBlockEntity;
import com.arrl.radiocraft.common.blockentities.PhonePaBlockEntity;
import com.arrl.radiocraft.common.blockentities.TelephoneBlockEntity;
import com.arrl.radiocraft.common.blocks.PhonePaBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.radio.voice.SpeakerKind;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Phone PA (2026-11-02): answers at once, plays through the speakers touching it or on its data wire, keeps an
 * extension set on its screen, can be limited to local calls, and is passed over by All-Call.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class PhonePaGameTests {

	private static final BlockPos PABX = new BlockPos(5, 2, 5), PHONE = PABX.north(), PA = PABX.south(),
			DESK = PA.south(), WALL = PA.east(4), HORN = PA.west(4), OTHER_PABX = PABX.east(4), OTHER_PHONE = OTHER_PABX.north();

	private static PhonePaBlockEntity pa(GameTestHelper helper) {
		return GameTestUtil.blockEntity(helper, PA, PhonePaBlockEntity.class);
	}

	private static TelephoneBlockEntity phone(GameTestHelper helper, BlockPos pos) {
		return GameTestUtil.blockEntity(helper, pos, TelephoneBlockEntity.class);
	}

	private static PabxBlockEntity pabx(GameTestHelper helper, BlockPos pos) {
		return GameTestUtil.blockEntity(helper, pos, PabxBlockEntity.class);
	}

	private static void build(GameTestHelper helper) {
		helper.setBlock(PABX, RadiocraftBlocks.PABX.get());
		helper.setBlock(PHONE, RadiocraftBlocks.TELEPHONE.get());
		helper.setBlock(PA, RadiocraftBlocks.PHONE_PA.get());
	}

	/** A call is taken at once and ends with the caller's hang up; its lamp follows. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
	public static void answersAtOnce(GameTestHelper helper) {
		build(helper);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(pa(helper).isNumbered() && phone(helper, PHONE).isNumbered() && pabx(helper, PABX).isPowered(), "not numbered yet"))
				.thenExecute(() -> {
					helper.assertTrue(pa(helper).save("500", false), "500 refused: " + pa(helper).getStatusKey());
					phone(helper, PHONE).dial("500");
					helper.assertTrue(phone(helper, PHONE).getState() == TelephoneBlockEntity.State.IN_CALL, "not answered: " + phone(helper, PHONE).getStatus());
					helper.assertTrue(pa(helper).getState() == TelephoneBlockEntity.State.IN_CALL, "the PA is " + pa(helper).getState());
					helper.assertFalse(pa(helper).canTransmitVoice(), "a PA has no microphone");
				})
				.thenWaitUntil(() -> helper.assertTrue(helper.getBlockState(PA).getValue(PhonePaBlock.LIT), "the lamp is off in a call"))
				.thenExecute(() -> {
					phone(helper, PHONE).hangUp();
					helper.assertTrue(pa(helper).getState() == TelephoneBlockEntity.State.IDLE, "the PA stayed in the call");
				})
				.thenWaitUntil(() -> helper.assertFalse(helper.getBlockState(PA).getValue(PhonePaBlock.LIT), "the lamp stayed on"))
				.thenSucceed();
	}

	/** A desk speaker touching it, a desk speaker and a wall speaker and a horn on its data wire; a speaker on no wire is left out. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
	public static void findsSpeakersTouchingAndOnItsWire(GameTestHelper helper) {
		build(helper);
		helper.setBlock(DESK, RadiocraftBlocks.LARGE_SPEAKER.get());
		helper.setBlock(WALL, RadiocraftBlocks.WALL_SPEAKER.get());
		helper.setBlock(HORN, RadiocraftBlocks.LOUDSPEAKER.get());
		BlockPos wiredDesk = PA.east(4).south();
		helper.setBlock(wiredDesk, RadiocraftBlocks.PREMIUM_SPEAKER_IRON.get());
		helper.setBlock(PA.north(4).west(6), RadiocraftBlocks.LARGE_SPEAKER.get());
		for(int i = 1; i < 4; i++) {
			GameTestUtil.placeWire(helper, PA.east(i), RadiocraftBlocks.DATA_WIRE.get());
			GameTestUtil.placeWire(helper, PA.west(i), RadiocraftBlocks.DATA_WIRE.get());
		}
		GameTestUtil.placeWire(helper, PA.east(3).south(), RadiocraftBlocks.DATA_WIRE.get());
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(pa(helper).getSpeakers().size() == 4, "speakers " + pa(helper).getSpeakers()))
				.thenExecute(() -> {
					java.util.List<SpeakerKind> kinds = pa(helper).getSpeakers().stream().map(PhonePaBlockEntity.Target::kind).toList();
					helper.assertTrue(kinds.contains(SpeakerKind.STANDARD) && kinds.contains(SpeakerKind.PREMIUM)
							&& kinds.contains(SpeakerKind.WALL) && kinds.contains(SpeakerKind.LOUDSPEAKER), "kinds " + kinds);
				})
				.thenSucceed();
	}

	/** Local calls only: its own PABX and a linked one get through, another master number does not; clearing it lets them. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public static void localOnlyLimitsCallers(GameTestHelper helper) {
		build(helper);
		helper.setBlock(OTHER_PABX, RadiocraftBlocks.PABX.get());
		helper.setBlock(OTHER_PHONE, RadiocraftBlocks.TELEPHONE.get());
		for(int i = 1; i < 4; i++)
			GameTestUtil.placeWire(helper, PABX.east(i), RadiocraftBlocks.DATA_WIRE.get());
		helper.startSequence()
				.thenExecute(() -> {
					pabx(helper, PABX).setMaster("602");
					pabx(helper, OTHER_PABX).setMaster("603");
				})
				.thenWaitUntil(() -> helper.assertTrue(pa(helper).isNumbered() && phone(helper, PHONE).isNumbered() && phone(helper, OTHER_PHONE).isNumbered(), "not numbered yet"))
				.thenExecute(() -> {
					helper.assertTrue(pa(helper).save("500", true), "500 refused: " + pa(helper).getStatusKey());
					TelephoneBlockEntity far = phone(helper, OTHER_PHONE);
					far.dial("602500");
					helper.assertTrue(pa(helper).getState() == TelephoneBlockEntity.State.IDLE, "another PABX got through");
					helper.assertTrue(far.getStatus().equals(TelephoneBlockEntity.LOCAL_ONLY), "status " + far.getStatus());
					phone(helper, PHONE).dial("500");
					helper.assertTrue(pa(helper).getState() == TelephoneBlockEntity.State.IN_CALL, "its own PABX did not: " + phone(helper, PHONE).getStatus());
					phone(helper, PHONE).hangUp();

					pabx(helper, OTHER_PABX).setMaster("602"); // Linked now: local.
					far.dial("500");
					helper.assertTrue(pa(helper).getState() == TelephoneBlockEntity.State.IN_CALL, "a linked PABX did not: " + far.getStatus());
					far.hangUp();

					pabx(helper, OTHER_PABX).setMaster("603");
					helper.assertTrue(pa(helper).save("500", false), "saving failed");
					far.dial("602500");
					helper.assertTrue(pa(helper).getState() == TelephoneBlockEntity.State.IN_CALL, "unlimited, another PABX did not get through: " + far.getStatus());
					far.hangUp();
				})
				.thenSucceed();
	}

	/** An extension set on its screen stays when the PABX renumbers, cannot be one in use, and is given back by saving none. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public static void keepsItsExtension(GameTestHelper helper) {
		build(helper);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(pa(helper).isNumbered() && phone(helper, PHONE).isNumbered(), "not numbered yet"))
				.thenExecute(() -> {
					helper.assertFalse(pa(helper).save(Integer.toString(phone(helper, PHONE).getExtension()), false), "a phone's extension was taken");
					helper.assertTrue(pa(helper).getStatusKey().equals("screen.radiocraft.phone_pa.extension_taken"), "status " + pa(helper).getStatusKey());
					helper.assertTrue(pa(helper).save("700", false), "700 refused");
					pabx(helper, PABX).setFirstExtension(4000);
				})
				.thenWaitUntil(() -> helper.assertTrue(phone(helper, PHONE).getExtension() == 4000, "phone " + phone(helper, PHONE).getExtension()))
				.thenExecute(() -> {
					helper.assertTrue(pa(helper).getExtension() == 700, "PA " + pa(helper).getExtension());
					helper.assertTrue(pa(helper).save("", false), "saving none failed");
				})
				.thenWaitUntil(() -> helper.assertTrue(pa(helper).getExtension() == 4001, "PA back at " + pa(helper).getExtension()))
				.thenSucceed();
	}

	/** All-Call rings the phones, and does not let the PA answer for them. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
	public static void allCallPassesItBy(GameTestHelper helper) {
		build(helper);
		BlockPos second = PABX.west();
		helper.setBlock(second, RadiocraftBlocks.TELEPHONE.get());
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(pa(helper).isNumbered() && phone(helper, PHONE).isNumbered() && phone(helper, second).isNumbered(), "not numbered yet"))
				.thenExecute(() -> {
					helper.assertTrue(pabx(helper, PABX).save("", "100", "999", ""), "saving failed: " + pabx(helper, PABX).getStatusKey());
					phone(helper, PHONE).dial("999");
					helper.assertTrue(phone(helper, second).getState() == TelephoneBlockEntity.State.RINGING, "the phone did not ring: " + phone(helper, PHONE).getStatus());
					helper.assertTrue(pa(helper).getState() == TelephoneBlockEntity.State.IDLE, "the PA took the All-Call");
					phone(helper, PHONE).hangUp();
				})
				.thenSucceed();
	}
}
