package com.retroconsole.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ScreenBlock extends BaseEntityBlock {

    public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);

    /** Куда смотрит экран (стена) / горизонтальная ось картинки (пол/потолок). */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** NORTH = на стене, UP = на полу экраном вверх, DOWN = на потолке экраном вниз. */
    public static final EnumProperty<Direction> ORIENTATION =
            EnumProperty.create("orientation", Direction.class,
                    Direction.NORTH, Direction.UP, Direction.DOWN);

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ORIENTATION, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ORIENTATION);
    }

    /** Как у мониторов CC: сильный наклон взгляда кладёт экран на пол/потолок. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        float pitch = context.getPlayer() != null ? context.getPlayer().getXRot() : 0.0f;
        Direction orientation;
        if (pitch > 66.0f) {
            orientation = Direction.UP;
        } else if (pitch < -66.0f) {
            orientation = Direction.DOWN;
        } else {
            orientation = Direction.NORTH;
        }
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(ORIENTATION, orientation);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !oldState.is(this)) {
            ScreenMultiblocks.rebuildAround(level, pos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !newState.is(this)) {
            ScreenMultiblocks.rebuildAfterRemoval(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** BUGFIX: without a loot table the block did not drop; drop the block directly. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of(new ItemStack(this));
    }
}
