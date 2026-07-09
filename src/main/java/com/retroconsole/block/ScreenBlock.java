package com.retroconsole.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ScreenBlock extends BaseEntityBlock {

    public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);

    /** Screen-to-console link radius; shared by all lookups and scans. */
    public static final int LINK_RADIUS = 16;

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

    /**
     * OPTIMIZATION: this used to scan 33³ ≈ 36000 positions with getBlockEntity on
     * every screen placement. Nearest console now comes from ConsoleRegistry in O(consoles).
     */
    private static void linkToNearestConsole(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
            BlockPos console = ConsoleRegistry.nearestWithin(level, pos, LINK_RADIUS);
            if (console != null) {
                screen.setConsolePos(console);
            }
        }
    }

    /** BUGFIX: without a loot table the block did not drop; drop the block directly. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of(new ItemStack(this));
    }
}