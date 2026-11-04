package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.be_networks.DataWire;
import com.arrl.radiocraft.common.blockentities.FieldPhoneBlockEntity;
import com.arrl.radiocraft.common.blocks.FieldPhoneBlock;
import com.arrl.radiocraft.common.init.RadiocraftBlocks;
import com.arrl.radiocraft.common.items.TelephoneHandsetItem;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * The TA-43 field phone (2026-11-04): phones on one data wire are a party line. Picking up rings the rest until one
 * answers; six phones to a line, twelve with power. The wire's memory lets a line run through unloaded chunks.
 */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class FieldPhoneGameTests {

	private static final String BATCH = "field_phone";
	/** The line runs east along z = 2; phones sit north of it, one every other block. */
	private static final BlockPos LINE = new BlockPos(1, 2, 2);

	private static BlockPos phonePos(int i) {
		return LINE.east(i * 2).north();
	}

	private static FieldPhoneBlockEntity phone(GameTestHelper helper, int i) {
		return GameTestUtil.blockEntity(helper, phonePos(i), FieldPhoneBlockEntity.class);
	}

	/** A data wire {@code length} long and a phone at every other block of it. */
	private static void line(GameTestHelper helper, int phones, int length) {
		for(int i = 0; i < length; i++)
			GameTestUtil.placeWire(helper, LINE.east(i), RadiocraftBlocks.DATA_WIRE.get());
		for(int i = 0; i < phones; i++)
			helper.setBlock(phonePos(i), RadiocraftBlocks.FIELD_PHONE.get());
	}

	private static ServerPlayer player(GameTestHelper helper, String name, int at) {
		FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
		player.getInventory().clearContent();
		if(!helper.getLevel().players().contains(player))
			helper.getLevel().players().add(player);
		Vec3 pos = helper.absoluteVec(Vec3.atBottomCenterOf(phonePos(at).north()));
		player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		return player;
	}

	/** Picking up rings the others; the first to answer stops the rest; hanging up leaves the line quiet. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = BATCH)
	public static void pickUpRingsTheRestUntilOneAnswers(GameTestHelper helper) {
		line(helper, 3, 6);
		ServerPlayer caller = player(helper, "field-caller", 0), answerer = player(helper, "field-answerer", 1);
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					helper.useBlock(phonePos(0), caller);
					helper.assertTrue(TelephoneHandsetItem.isHandsetOf(caller.getMainHandItem(), phone(helper, 0)), "no handset in the hand: " + caller.getMainHandItem());
					helper.assertTrue(helper.getBlockState(phonePos(0)).getValue(FieldPhoneBlock.OFF_HOOK), "the handset still shows");
					helper.assertTrue(phone(helper, 1).isRinging() && phone(helper, 2).isRinging(), "the others are not ringing: line " + phone(helper, 0).getLine().size());
					helper.assertFalse(phone(helper, 0).isRinging(), "the caller rings itself");
					helper.assertFalse(phone(helper, 0).canTransmitVoice(), "talking to nobody");
				})
				.thenIdle(3)
				.thenExecute(() -> {
					helper.useBlock(phonePos(1), answerer);
					helper.assertTrue(phone(helper, 0).canTransmitVoice() && phone(helper, 1).canTransmitVoice(), "the two handsets up cannot talk");
				})
				.thenIdle(2)
				.thenExecute(() -> {
					helper.assertFalse(phone(helper, 1).isRinging(), "the answered phone rings on");
					helper.assertFalse(phone(helper, 2).isRinging(), "the third rings on after an answer");
					helper.useBlock(phonePos(1), answerer); // With its handset: hangs up.
					helper.assertTrue(answerer.getMainHandItem().isEmpty(), "the handset stayed in the hand");
					helper.assertFalse(phone(helper, 0).canTransmitVoice(), "talking to a phone hung up");
					helper.useBlock(phonePos(0), caller);
					helper.useBlock(phonePos(0), caller); // Picked up again: the line was quiet, so the others ring again.
					helper.assertTrue(phone(helper, 1).isRinging(), "a second pick-up did not ring");
					helper.useBlock(phonePos(0), caller);
				})
				.thenIdle(2)
				.thenExecute(() -> {
					helper.assertFalse(phone(helper, 1).isRinging() || phone(helper, 2).isRinging(), "they ring on after the caller hung up");
					helper.getLevel().players().remove(caller);
					helper.getLevel().players().remove(answerer);
				})
				.thenSucceed();
	}

	/** Seven phones are too many for a line with no power: it is dead. A power wire on one of them takes it to twelve. */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = BATCH)
	public static void sixToALineTwelveWithPower(GameTestHelper helper) {
		line(helper, 7, 13);
		ServerPlayer caller = player(helper, "field-overloaded", 0);
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					helper.useBlock(phonePos(0), caller);
					helper.assertTrue(phone(helper, 0).isOverloaded(), "seven phones on an unpowered line work: line " + phone(helper, 0).getLine().size());
					helper.assertFalse(phone(helper, 1).isRinging(), "a dead line rang");
					helper.useBlock(phonePos(0), caller);
					BlockPos last = phonePos(6);
					GameTestUtil.placeWire(helper, last.above(), RadiocraftBlocks.WIRE.get());
					helper.setBlock(last.above(2), RadiocraftBlocks.LARGE_BATTERY.get());
				})
				.thenWaitUntil(() -> {
					phone(helper, 0).refreshLine();
					helper.assertTrue(phone(helper, 0).isLinePowered() && !phone(helper, 0).isOverloaded(), "a power wire on one phone did not take the line to twelve");
				})
				.thenExecute(() -> {
					helper.useBlock(phonePos(0), caller);
					helper.assertTrue(phone(helper, 6).isRinging(), "the far phone did not ring");
					helper.useBlock(phonePos(0), caller);
					helper.getLevel().players().remove(caller);
				})
				.thenSucceed();
	}

	/**
	 * A walk along loaded wire is remembered, so with the middle of the wire unloaded the far phone is still found.
	 * Wire taken up is forgotten on the next tick, and the line is cut.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200, batch = BATCH)
	public static void lineRunsThroughUnloadedWire(GameTestHelper helper) {
		line(helper, 1, 12);
		helper.setBlock(LINE.east(11).north(), RadiocraftBlocks.FIELD_PHONE.get()); // At the far end.
		BlockPos near = helper.absolutePos(phonePos(0)), far = helper.absolutePos(LINE.east(11).north());
		BlockPos gapFrom = helper.absolutePos(LINE.east(4)), gapTo = helper.absolutePos(LINE.east(9));
		Predicate<BlockPos> middle = pos -> pos.getZ() == gapFrom.getZ() && pos.getY() == gapFrom.getY()
				&& pos.getX() >= gapFrom.getX() && pos.getX() <= gapTo.getX();
		helper.startSequence()
				.thenIdle(2)
				.thenExecute(() -> {
					helper.assertTrue(DataWire.reached(helper.getLevel(), near).contains(far), "the far phone is not on the loaded line");
					helper.assertTrue(DataWire.reachedAsIfUnloaded(helper.getLevel(), near, 1000, middle).contains(far), "the remembered wire does not reach the far phone");
					helper.assertTrue(DataWire.reachedAsIfUnloaded(helper.getLevel(), far, 1000, middle).contains(near), "the remembered wire does not reach back");
					helper.setBlock(LINE.east(6), Blocks.AIR);
				})
				.thenIdle(2)
				.thenExecute(() -> {
					helper.assertFalse(DataWire.reached(helper.getLevel(), near).contains(far), "the cut wire still reaches");
					helper.assertFalse(DataWire.reachedAsIfUnloaded(helper.getLevel(), near, 1000, middle).contains(far), "the memory still has the wire taken up");
				})
				.thenSucceed();
	}
}
