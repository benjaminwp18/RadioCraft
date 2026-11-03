package com.arrl.radiocraft.common.blocks;

import com.arrl.radiocraft.common.blockentities.PhonePaBlockEntity;
import com.arrl.radiocraft.common.blockentities.TelephoneBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * A Phone PA: call its extension and the caller's voice comes out of the speakers touching it or on its data wire
 * ({@link PhonePaBlockEntity}). Its lamp is lit during a call; right click to set it up. The size of a phone PABX.
 */
public class PhonePaBlock extends AbstractNetworkBlock {

	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	private static final Map<Direction, VoxelShape> SHAPES = SpeakerBlock.shapes(new double[] { 0.0D, 0.0D, 2.75D, 16.0D, 14.0D, 14.0D });

	@SuppressWarnings("this-escape")
	public PhonePaBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.@NotNull Builder<Block, BlockState> builder) {
		builder.add(FACING, LIT);
	}

	@Override
	public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hit) {
		if(!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof PhonePaBlockEntity pa)
			serverPlayer.openMenu(pa, pos);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
		return simpleCodec(PhonePaBlock::new);
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
		return new PhonePaBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(@NotNull Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
		return level.isClientSide || type != com.arrl.radiocraft.common.init.RadiocraftBlockEntities.PHONE_PA.get() ? null : TelephoneBlockEntity::tick;
	}

	@Override
	public void appendHoverText(@NotNull net.minecraft.world.item.ItemStack stack, net.minecraft.world.item.Item.@NotNull TooltipContext context,
								@NotNull java.util.List<net.minecraft.network.chat.Component> tooltip, @NotNull net.minecraft.world.item.TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);
		tooltip.add(net.minecraft.network.chat.Component.translatable("tooltip.radiocraft.phone_pa").withStyle(net.minecraft.ChatFormatting.GRAY));
	}
}
