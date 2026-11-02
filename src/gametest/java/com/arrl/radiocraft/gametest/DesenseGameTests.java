package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.radio.Band;
import com.arrl.radiocraft.common.radio.TransmitterLog;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** A transmitting antenna desenses a receiving one close by (2026-11-02), unless it is the station being heard, or far enough off. */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class DesenseGameTests {

	@GameTest(template = GameTestUtil.EMPTY)
	public static void nearbyTransmitterDesenses(GameTestHelper helper) {
		BlockPos here = helper.absolutePos(new BlockPos(5, 2, 5));
		Object near = new Object();
		float on = 146_000_000.0F;
		TransmitterLog.record(near, helper.getLevel(), here.east(2), Band.getBand("2m"), on);
		double close = TransmitterLog.desense(helper.getLevel(), here, on + 500_000.0F, null, null);
		helper.assertTrue(close < 1.0D, "500 kHz off left " + close);
		helper.assertTrue(TransmitterLog.desense(helper.getLevel(), here, on, null, null) < close, "on frequency should be worse");
		helper.assertTrue(TransmitterLog.desense(helper.getLevel(), here, on + 2_000_000.0F, null, null) == 1.0D, "2 MHz off is clear");
		helper.assertTrue(TransmitterLog.desense(helper.getLevel(), here.west(4), on, null, null) == 1.0D, "6 blocks off is clear");
		helper.assertTrue(TransmitterLog.desense(helper.getLevel(), here, on, near, null) == 1.0D, "the station being heard does not desense itself");
		helper.assertTrue(TransmitterLog.desense(helper.getLevel(), here, on, null, near) == 1.0D, "an antenna does not desense itself");
		helper.succeed();
	}
}
