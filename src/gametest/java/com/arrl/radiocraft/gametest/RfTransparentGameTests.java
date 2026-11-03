package com.arrl.radiocraft.gametest;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.radio.terrain.TerrainAtlas;
import com.arrl.radiocraft.common.radio.terrain.TerrainPropagation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** RF passes through wood planks, stairs and slabs, glass, wool and leaves (2026-11-02), but not stone or logs. */
@GameTestHolder(Radiocraft.MOD_ID)
@PrefixGameTestTemplate(false)
public class RfTransparentGameTests {

	private static final BlockPos STATION = new BlockPos(5, 2, 5), ROOF = STATION.above(4);
	private static final List<Block> PASSED = List.of(Blocks.OAK_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.BIRCH_SLAB, Blocks.GLASS,
			Blocks.GLASS_PANE, Blocks.WHITE_WOOL, Blocks.OAK_LEAVES);

	/** The height the terrain map takes from a column, with the given roof. */
	private static int surface(GameTestHelper helper) {
		BlockPos at = helper.absolutePos(STATION);
		LevelChunk chunk = helper.getLevel().getChunkAt(at);
		return TerrainAtlas.surface(chunk, at.getX() & 15, at.getZ() & 15);
	}

	@GameTest(template = GameTestUtil.EMPTY)
	public static void terrainMapSeesThroughARoof(GameTestHelper helper) {
		helper.setBlock(STATION, Blocks.STONE);
		for(int up = 1; up < 30; up++) // The test's barrier ceiling would count as terrain.
			helper.setBlock(ROOF.above(up), Blocks.AIR);
		int floor = helper.absolutePos(STATION).getY() + 1;
		for(Block roof : PASSED) {
			helper.setBlock(ROOF, roof);
			helper.assertTrue(surface(helper) == floor, roof + " counted as terrain: " + surface(helper) + ", floor " + floor);
		}
		for(Block roof : List.of(Blocks.STONE, Blocks.OAK_LOG)) {
			helper.setBlock(ROOF, roof);
			helper.assertTrue(surface(helper) == helper.absolutePos(ROOF).getY() + 1, roof + " did not count: " + surface(helper));
		}
		helper.succeed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 200)
	public static void handheldIsNotIndoorsUnderARoofRfPasses(GameTestHelper helper) {
		BlockPos at = helper.absolutePos(STATION);
		helper.setBlock(ROOF, Blocks.STONE);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(TerrainPropagation.isIndoors(helper.getLevel(), at), "not indoors under stone"))
				.thenExecute(() -> {
					for(Block roof : PASSED) {
						helper.setBlock(ROOF, roof);
						helper.assertFalse(TerrainPropagation.isIndoors(helper.getLevel(), at), "indoors under " + roof);
					}
				})
				.thenSucceed();
	}
}
