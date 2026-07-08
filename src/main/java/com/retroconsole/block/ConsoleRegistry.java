package com.retroconsole.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверный реестр позиций консолей по измерениям.
 *
 * Заменяет сканирование 33^3 ≈ 36000 getBlockEntity() в
 * ScreenBlock.linkToNearestConsole: поиск ближайшей консоли теперь
 * O(число консолей).
 *
 * Наполнение: RetroConsoleBlock.onPlace и RetroConsoleBlockEntity.onLoad
 * (загрузка чанка). Очистка: RetroConsoleBlock.onRemove,
 * RetroConsoleBlockEntity.onChunkUnloaded/setRemoved и ServerStoppingEvent
 * (ServerTickHandler).
 */
public final class ConsoleRegistry {

    private static final Map<ResourceKey<Level>, Set<BlockPos>> CONSOLES = new ConcurrentHashMap<>();

    private ConsoleRegistry() {}

    public static void add(Level level, BlockPos pos) {
        if (level == null || level.isClientSide()) return;
        CONSOLES.computeIfAbsent(level.dimension(), k -> ConcurrentHashMap.newKeySet())
                .add(pos.immutable());
    }

    public static void remove(Level level, BlockPos pos) {
        if (level == null || level.isClientSide()) return;
        Set<BlockPos> set = CONSOLES.get(level.dimension());
        if (set != null) set.remove(pos.immutable());
    }

    /** Ближайшая консоль в радиусе, null если нет. */
    public static BlockPos nearestWithin(Level level, BlockPos from, int radius) {
        return nearestWithin(level, from, radius, null);
    }

    /** То же, но с исключением позиции (для перепривязки при ломании консоли). */
    public static BlockPos nearestWithin(Level level, BlockPos from, int radius, BlockPos exclude) {
        Set<BlockPos> set = CONSOLES.get(level.dimension());
        if (set == null) return null;
        long rSq = (long) radius * radius;
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        for (BlockPos p : set) {
            if (p.equals(exclude)) continue;
            long d = (long) p.distSqr(from);
            if (d <= rSq && d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Вызывается на ServerStoppingEvent — чтобы позиции не пережили мир. */
    public static void clear() {
        CONSOLES.clear();
    }
}