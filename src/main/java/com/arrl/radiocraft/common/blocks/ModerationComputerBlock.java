package com.arrl.radiocraft.common.blocks;

import com.arrl.radiocraft.Radiocraft;
import com.arrl.radiocraft.common.blockentities.ModerationComputerBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The Radio Moderation Computer: a beige late-90s PC (desktop case, CRT, keyboard) for server admins, not craftable.
 * Placing it makes it the placer's ({@link ModerationComputerBlockEntity}); right click opens its screen for its owner
 * and the admins they added, and tells anyone else whose it is.
 */
public class ModerationComputerBlock extends BaseEntityBlock {

	public static final MapCodec<ModerationComputerBlock> CODEC = simpleCodec(ModerationComputerBlock::new);
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	private static final Map<Direction, VoxelShape> SHAPES = shapes();

	/** The model faces north: the keyboard in front, the desktop case behind it, the monitor on the case. */
	private static Map<Direction, VoxelShape> shapes() {
		Map<Direction, VoxelShape> keyboard = SpeakerBlock.shapes(new double[] { 2.0D, 0.0D, 0.5D, 14.0D, 1.5D, 5.0D });
		Map<Direction, VoxelShape> desktop = SpeakerBlock.shapes(new double[] { 1.0D, 0.0D, 5.5D, 15.0D, 4.0D, 15.5D });
		Map<Direction, VoxelShape> monitor = SpeakerBlock.shapes(new double[] { 2.5D, 4.0D, 6.0D, 13.5D, 14.5D, 15.0D });
		Map<Direction, VoxelShape> out = new EnumMap<>(Direction.class);
		for(Direction direction : keyboard.keySet())
			out.put(direction, Shapes.or(keyboard.get(direction), desktop.get(direction), monitor.get(direction)));
		return out;
	}

	@SuppressWarnings("this-escape")
	public ModerationComputerBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.@NotNull Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(@NotNull BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	/** Whoever places it owns it, if they may (see {@link ModerationComputerBlockEntity#mayClaim}). */
	@Override
	public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state, @Nullable LivingEntity placer, @NotNull ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if(placer instanceof ServerPlayer player && level.getBlockEntity(pos) instanceof ModerationComputerBlockEntity computer && !computer.claim(player))
			player.displayClientMessage(message("unowned").withStyle(ChatFormatting.YELLOW), true);
	}

	@Override
	protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, @NotNull Player player, @NotNull BlockHitResult hit) {
		if(!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof ModerationComputerBlockEntity computer)
			open(serverPlayer, computer);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/**
	 * Opens the screen for its owner or an admin; an operator using one nobody owns claims it first. Anyone else is told
	 * whose it is over the hotbar. Returns true if it opened.
	 */
	public static boolean open(ServerPlayer player, ModerationComputerBlockEntity computer) {
		if(computer.getOwner() == null && computer.claim(player))
			player.displayClientMessage(message("claimed").withStyle(ChatFormatting.GREEN), true);
		if(!computer.canOpen(player)) {
			player.displayClientMessage(computer.getOwner() == null ? message("locked_unowned").withStyle(ChatFormatting.RED)
					: message("locked", computer.getOwnerName()).withStyle(ChatFormatting.RED), true);
			return false;
		}
		player.openMenu(computer, computer.getBlockPos());
		return true;
	}

	private static net.minecraft.network.chat.MutableComponent message(String key, Object... args) {
		return Component.translatable(Radiocraft.translationKey("message", "moderation_computer." + key), args);
	}

	@Override
	protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level, @NotNull BlockPos pos, @NotNull CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	@Override
	public @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Nullable
	@Override
	public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
		return new ModerationComputerBlockEntity(pos, state);
	}
}
