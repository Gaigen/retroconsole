package com.retroconsole.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ScreenBlock extends BaseEntityBlock {

    public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);

    public ScreenBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

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
        if (!level.isClientSide()) {
            linkToNearestConsole(level, pos);
        }
    }

    private void linkToNearestConsole(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
            int searchRadius = 16;
            BlockPos closest = null;
            double closestDist = Double.MAX_VALUE;

            for (BlockPos candidate : BlockPos.betweenClosed(
                    pos.offset(-searchRadius, -searchRadius, -searchRadius),
                    pos.offset(searchRadius, searchRadius, searchRadius))) {
                if (level.getBlockEntity(candidate) instanceof RetroConsoleBlockEntity) {
                    double dist = candidate.distSqr(pos);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = candidate.immutable();
                    }
                }
            }

            if (closest != null) {
                screen.setConsolePos(closest);
            }
        }
    }
}
