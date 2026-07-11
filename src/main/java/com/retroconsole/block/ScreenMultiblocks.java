package com.retroconsole.block;

import com.retroconsole.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Серверная сборка мультиблочных экранов (в духе мониторов CC:Tweaked).
 * Сервер — источник истины: каждому ScreenBlockEntity записываются
 * xIndex/yIndex/gridWidth/gridHeight/consolePos, клиент только читает.
 *
 * Инварианты:
 *  - группа = строгий прямоугольник в одной плоскости, одинаковые FACING+ORIENTATION;
 *  - не-прямоугольный кластер детерминированно нарезается на несколько прямоугольников;
 *  - привязка к консоли — только касание (6-соседство) любого блока группы.
 */
public final class ScreenMultiblocks {

    private ScreenMultiblocks() {}

    /** Отложенная пересборка (после загрузки чанка / из onLoad). */
    public static void scheduleRebuild(ServerLevel level, BlockPos pos) {
        BlockPos target = pos.immutable();
        level.getServer().execute(() -> {
            if (level.isLoaded(target)
                    && level.getBlockState(target).getBlock() instanceof ScreenBlock) {
                rebuildAround(level, target);
            }
        });
    }

    /** Пересобрать раздел кластера, содержащего pos. Идемпотентно. */
    public static void rebuildAround(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScreenBlock)) return;

        Direction right = rightOf(state);
        Direction down = downOf(state);

        Set<BlockPos> cluster = collectCluster(level, pos, state, right, down);

        int minU = Integer.MAX_VALUE, minV = Integer.MAX_VALUE;
        for (BlockPos p : cluster) {
            minU = Math.min(minU, dot(p, right));
            minV = Math.min(minV, dot(p, down));
        }
        Map<Long, BlockPos> cells = new HashMap<>(cluster.size());
        int maxU = 0, maxV = 0;
        for (BlockPos p : cluster) {
            int u = dot(p, right) - minU;
            int v = dot(p, down) - minV;
            maxU = Math.max(maxU, u);
            maxV = Math.max(maxV, v);
            cells.put(key(u, v), p);
        }

        boolean[][] used = new boolean[maxU + 1][maxV + 1];
        for (int v = 0; v <= maxV; v++) {
            for (int u = 0; u <= maxU; u++) {
                if (used[u][v] || !cells.containsKey(key(u, v))) continue;

                int w = 1;
                while (u + w <= maxU && !used[u + w][v] && cells.containsKey(key(u + w, v))) w++;
                int h = 1;
                outer:
                while (v + h <= maxV) {
                    for (int du = 0; du < w; du++) {
                        if (used[u + du][v + h] || !cells.containsKey(key(u + du, v + h))) break outer;
                    }
                    h++;
                }

                applyRect(level, cells, used, u, v, w, h);
            }
        }
    }

    /** После удаления экрана: пересобрать оставшихся соседей в той же плоскости. */
    public static void rebuildAfterRemoval(Level level, BlockPos removed, BlockState oldState) {
        if (level.isClientSide()) return;
        Direction right = rightOf(oldState);
        Direction down = downOf(oldState);
        for (Direction d : new Direction[]{right, right.getOpposite(), down, down.getOpposite()}) {
            BlockPos n = removed.relative(d);
            if (level.getBlockState(n).getBlock() instanceof ScreenBlock) {
                rebuildAround(level, n);
            }
        }
    }

    /** Консоль поставили/сломали/загрузили — перелинковать примыкающие группы. */
    public static void onConsoleChanged(Level level, BlockPos consolePos) {
        if (level.isClientSide()) return;
        for (Direction d : Direction.values()) {
            BlockPos n = consolePos.relative(d);
            if (level.getBlockState(n).getBlock() instanceof ScreenBlock) {
                rebuildAround(level, n);
            }
        }
    }

    private static void applyRect(Level level, Map<Long, BlockPos> cells, boolean[][] used,
                                  int u, int v, int w, int h) {
        List<BlockPos> members = new ArrayList<>(w * h);
        for (int dv = 0; dv < h; dv++) {
            for (int du = 0; du < w; du++) {
                used[u + du][v + dv] = true;
                members.add(cells.get(key(u + du, v + dv)));
            }
        }
        BlockPos console = findAdjacentConsole(level, members);
        int i = 0;
        for (int dv = 0; dv < h; dv++) {
            for (int du = 0; du < w; du++) {
                if (level.getBlockEntity(members.get(i++)) instanceof ScreenBlockEntity sbe) {
                    sbe.setGrid(du, dv, w, h, console);
                }
            }
        }
    }

    private static BlockPos findAdjacentConsole(Level level, List<BlockPos> members) {
        for (BlockPos p : members) {
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (level.getBlockEntity(n) instanceof RetroConsoleBlockEntity) {
                    return n.immutable();
                }
            }
        }
        return BlockPos.ZERO;
    }

    private static Set<BlockPos> collectCluster(Level level, BlockPos start, BlockState state,
                                                Direction right, Direction down) {
        Direction facing = state.getValue(ScreenBlock.FACING);
        Direction orientation = state.getValue(ScreenBlock.ORIENTATION);
        Direction[] inPlane = {right, right.getOpposite(), down, down.getOpposite()};

        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start.immutable());
        queue.add(start.immutable());

        while (!queue.isEmpty() && visited.size() < ModConfig.maxScreenCluster()) {
            BlockPos cur = queue.poll();
            for (Direction d : inPlane) {
                BlockPos n = cur.relative(d);
                if (visited.contains(n)) continue;
                BlockState ns = level.getBlockState(n);
                if (!(ns.getBlock() instanceof ScreenBlock)) continue;
                if (ns.getValue(ScreenBlock.FACING) != facing) continue;
                if (ns.getValue(ScreenBlock.ORIENTATION) != orientation) continue;
                if (!(level.getBlockEntity(n) instanceof ScreenBlockEntity)) continue;
                visited.add(n.immutable());
                queue.add(n.immutable());
            }
        }
        return visited;
    }

    static Direction rightOf(BlockState state) {
        return state.getValue(ScreenBlock.FACING).getCounterClockWise();
    }

    static Direction downOf(BlockState state) {
        Direction facing = state.getValue(ScreenBlock.FACING);
        return switch (state.getValue(ScreenBlock.ORIENTATION)) {
            case UP -> facing;
            case DOWN -> facing.getOpposite();
            default -> Direction.DOWN;
        };
    }

    private static int dot(BlockPos p, Direction d) {
        return p.getX() * d.getStepX() + p.getY() * d.getStepY() + p.getZ() * d.getStepZ();
    }

    private static long key(int u, int v) {
        return ((long) u << 32) | (v & 0xFFFFFFFFL);
    }
}
