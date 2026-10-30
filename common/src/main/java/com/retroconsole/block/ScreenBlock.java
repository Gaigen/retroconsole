package com.retroconsole.block;

import com.retroconsole.item.ScreenItem;
import com.retroconsole.style.RetroTone;
import com.retroconsole.style.ScreenDesign;
import com.retroconsole.style.ScreenMount;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.UnaryOperator;

public class ScreenBlock extends BaseEntityBlock {


    /** Куда смотрит экран (стена) / горизонтальная ось картинки (пол/потолок). */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** NORTH = на стене, UP = на полу экраном вверх, DOWN = на потолке экраном вниз. */
    public static final EnumProperty<Direction> ORIENTATION =
            EnumProperty.create("orientation", Direction.class,
                    Direction.NORTH, Direction.UP, Direction.DOWN);

    /** Корпус телевизора: глубина, рамка и размер картинки берутся из него. */
    public static final EnumProperty<ScreenDesign> DESIGN =
            EnumProperty.create("design", ScreenDesign.class);

    /** Цвет корпуса; картинка и разъёмы от тона не зависят. */
    public static final EnumProperty<RetroTone> TONE =
            EnumProperty.create("tone", RetroTone.class);

    /** У какой грани блока стоит корпус: спереди или вплотную к задней стене. */
    public static final EnumProperty<ScreenMount> MOUNT =
            EnumProperty.create("mount", ScreenMount.class);

    // Only the shell is solid: a 2-unit panel must not block a doorway like a cube.
    private static final Map<ScreenDesign, Map<ScreenMount, Map<Direction, Map<Direction, VoxelShape>>>>
            SHAPES = createShapes();

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ORIENTATION, Direction.NORTH)
                .setValue(DESIGN, ScreenDesign.RETRO)
                .setValue(MOUNT, ScreenMount.FRONT)
                .setValue(TONE, RetroTone.CREAM));
    }

    private static Map<ScreenDesign, Map<ScreenMount, Map<Direction, Map<Direction, VoxelShape>>>> createShapes() {
        Map<ScreenDesign, Map<ScreenMount, Map<Direction, Map<Direction, VoxelShape>>>> shapes =
                new EnumMap<>(ScreenDesign.class);
        for (ScreenDesign design : ScreenDesign.values()) {
            double depth = design.depth() / 16.0;
            Map<ScreenMount, Map<Direction, Map<Direction, VoxelShape>>> byMount =
                    new EnumMap<>(ScreenMount.class);
            for (ScreenMount mount : ScreenMount.values()) {
                // Shapes are authored in the block's own frame; a back mount is the
                // same slab mirrored to the far side, so floor and ceiling swap.
                boolean back = mount == ScreenMount.BACK;
                VoxelShape wall = back ? Shapes.box(0, 0, 1 - depth, 1, 1, 1)
                        : Shapes.box(0, 0, 0, 1, 1, depth);
                VoxelShape floor = back ? Shapes.box(0, 0, 0, 1, depth, 1)
                        : Shapes.box(0, 1 - depth, 0, 1, 1, 1);
                VoxelShape ceiling = back ? Shapes.box(0, 1 - depth, 0, 1, 1, 1)
                        : Shapes.box(0, 0, 0, 1, depth, 1);
                Map<Direction, Map<Direction, VoxelShape>> byOrientation = new EnumMap<>(Direction.class);
                byOrientation.put(Direction.NORTH, ChassisShapes.horizontal(wall));
                byOrientation.put(Direction.UP, ChassisShapes.horizontal(floor));
                byOrientation.put(Direction.DOWN, ChassisShapes.horizontal(ceiling));
                byMount.put(mount, byOrientation);
            }
            shapes.put(design, byMount);
        }
        return shapes;
    }


    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ORIENTATION, DESIGN, MOUNT, TONE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(DESIGN))
                .get(state.getValue(MOUNT))
                .get(state.getValue(ORIENTATION))
                .get(state.getValue(FACING));
    }

    /**
     * Shell, finish and mount belong to the whole screen, so every gesture is applied
     * to the wall this block is part of: cycling a 3x2 display one cell at a time was
     * slow and easy to end up with a mixed frame. Shift+right-click with a bare hand
     * cycles the shell, a dye repaints and a plain right-click with another screen
     * flips the mount. All of it is cosmetic: grid assembly, the console link and the
     * video are untouched.
     *
     * <p>The mount cannot be sneak-driven: a sneaking player's click goes straight to
     * the held item and the block is never asked, so flipping uses a plain click.
     */
    @Override
    public InteractionResult use(BlockState state, Level level,
                                          BlockPos pos, Player player, InteractionHand hand,
                                          BlockHitResult hitResult) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof net.minecraft.world.item.DyeItem) {
            RetroTone tone = RetroTone.ofStack(stack);
            if (tone == null || tone == state.getValue(TONE)) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide()) {
                changeWall(level, pos, s -> s.setValue(TONE, tone));
                player.displayClientMessage(Component.translatable("retroconsole.tone.painted",
                        Component.translatable(tone.translationKey())), true);
            }
            return InteractionResult.SUCCESS;
        }
        if (stack.getItem() instanceof ScreenItem) {
            if (!level.isClientSide()) {
                ScreenMount mount = state.getValue(MOUNT).next();
                changeWall(level, pos, s -> s.setValue(MOUNT, mount));
                player.displayClientMessage(Component.translatable("retroconsole.screen.mount.changed",
                        Component.translatable(mount.translationKey())), true);
            }
            return InteractionResult.SUCCESS;
        }
        if (!stack.isEmpty() || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            ScreenDesign design = state.getValue(DESIGN).next();
            changeWall(level, pos, s -> s.setValue(DESIGN, design));
            player.displayClientMessage(Component.translatable("retroconsole.screen.design.changed",
                    Component.translatable(design.translationKey())), true);
        }
        return InteractionResult.SUCCESS;
    }

    /** Shell, finish and mount belong to the screen, not to one cell of it. */
    private static void changeWall(Level level, BlockPos pos, UnaryOperator<BlockState> change) {
        for (BlockPos cell : ScreenMultiblocks.clusterOf(level, pos)) {
            BlockState before = level.getBlockState(cell);
            BlockState after = change.apply(before);
            if (after != before) {
                level.setBlock(cell, after, Block.UPDATE_ALL);
            }
        }
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
        // A picked-up screen keeps the shell, finish and mount it was broken with.
        BlockState placed = defaultBlockState();
        net.minecraft.nbt.CompoundTag stored = context.getItemInHand().getTagElement("BlockStateTag");
        if (stored != null) {
            for (String key : stored.getAllKeys()) {
                var property = placed.getBlock().getStateDefinition().getProperty(key);
                if (property != null) placed = applyProperty(placed, property, stored.getString(key));
            }
        }
        return placed
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(ORIENTATION, orientation);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !oldState.is(this) && level instanceof ServerLevel sl) {
            scheduleAdjacentRebuilds(sl, pos, state);
        }
    }

    private static void scheduleAdjacentRebuilds(ServerLevel level, BlockPos pos, BlockState state) {
        ScreenMultiblocks.scheduleRebuild(level, pos);
        Direction right = ScreenMultiblocks.rightOf(state);
        Direction down = ScreenMultiblocks.downOf(state);
        for (Direction d : new Direction[]{right, right.getOpposite(), down, down.getOpposite()}) {
            BlockPos n = pos.relative(d);
            if (level.getBlockState(n).getBlock() instanceof ScreenBlock) {
                ScreenMultiblocks.scheduleRebuild(level, n);
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !newState.is(this)) {
            ScreenMultiblocks.rebuildAfterRemoval(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property,v)).orElse(state);
    }
}
