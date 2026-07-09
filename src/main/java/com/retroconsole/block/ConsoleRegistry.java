package com.retroconsole.block;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of console positions per dimension.
 *
 * <p>Replaces scanning 33³ ≈ 36000 getBlockEntity() calls in
 * ScreenBlock.linkToNearestConsole: nearest-console lookup is now O(consoles).
 *
 * <p>Populated by RetroConsoleBlock.onPlace and RetroConsoleBlockEntity.onLoad
 * (chunk load). Cleared by RetroConsoleBlock.onRemove,
 * RetroConsoleBlockEntity.onChunkUnloaded/setRemoved, and ServerStoppingEvent
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

    /** Nearest console within radius, or null if none. */
    public static BlockPos nearestWithin(Level level, BlockPos from, int radius) {
        return nearestWithin(level, from, radius, null);
    }

    /** Same as above, but excludes one position (when relinking screens after breaking a console). */
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

    /** Called on ServerStoppingEvent so positions do not outlive the world. */
    public static void clear() {
        CONSOLES.clear();
    }
}