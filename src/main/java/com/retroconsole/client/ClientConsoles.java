package com.retroconsole.client;

import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public class ClientConsoles {

    public record ScreenEntry(DynamicTexture tex, ResourceLocation id,
                               int[] frame, int width, int height) {}

    private static final Map<BlockPos, ScreenEntry> SCREENS = new HashMap<>();
    private static final Map<BlockPos, int[]> GRID_CACHE = new HashMap<>();
    private static int frameCounter = 0;

    public static void updateFrame(BlockPos pos, int[] frame, int width, int height) {
        pos = pos.immutable();
        ScreenEntry entry = SCREENS.get(pos);

        if (entry != null && (entry.width() != width || entry.height() != height)) {
            Minecraft.getInstance().getTextureManager().release(entry.id());
            entry = null;
        }

        if (entry == null) {
            DynamicTexture tex = new DynamicTexture(width, height, true);
            ResourceLocation id = Minecraft.getInstance().getTextureManager()
                    .register("retro_screen_" + pos.asLong(), tex);
            entry = new ScreenEntry(tex, id, new int[width * height], width, height);
            SCREENS.put(pos, entry);
        }

        System.arraycopy(frame, 0, entry.frame(), 0,
                Math.min(frame.length, entry.frame().length));

        var img = entry.tex().getPixels();
        if (img != null) {
            int w = entry.width();
            int h = entry.height();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = entry.frame()[y * w + x];
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            entry.tex().upload();
        }
    }

    public static ScreenEntry getScreen(BlockPos consolePos) {
        if (consolePos == null) return null;
        return SCREENS.get(consolePos.immutable());
    }

    public static void dispose(BlockPos pos) {
        pos = pos.immutable();
        ScreenEntry entry = SCREENS.remove(pos);
        if (entry != null) {
            Minecraft.getInstance().getTextureManager().release(entry.id());
        }
        GRID_CACHE.clear();
    }

    public static int[] getGridInfo(ScreenBlockEntity be) {
        BlockPos pos = be.getBlockPos().immutable();
        if (++frameCounter % 60 == 0) GRID_CACHE.clear();
        int[] cached = GRID_CACHE.get(pos);
        if (cached != null) return cached;

        Set<BlockPos> group = findScreenGroup(be);
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : group) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        int xSpan = maxX - minX + 1, zSpan = maxZ - minZ + 1;
        int gridW = Math.max(xSpan, zSpan), gridH = maxY - minY + 1;
        int gridX = (xSpan >= zSpan) ? pos.getX() - minX : pos.getZ() - minZ;
        int gridYFromTop = maxY - pos.getY();
        int[] result = {gridX, gridYFromTop, gridW, gridH};
        GRID_CACHE.put(pos, result);
        return result;
    }

    private static Set<BlockPos> findScreenGroup(ScreenBlockEntity be) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        BlockPos start = be.getBlockPos().immutable();
        BlockPos consolePos = be.getConsolePos();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) continue;
                        BlockPos neighbor = current.offset(dx, dy, dz);
                        if (visited.contains(neighbor) || be.getLevel() == null) continue;
                        var blockEntity = be.getLevel().getBlockEntity(neighbor);
                        if (blockEntity instanceof ScreenBlockEntity sbe
                                && consolePos != null && consolePos.equals(sbe.getConsolePos())) {
                            visited.add(neighbor);
                            queue.add(neighbor);
                        }
                    }
        }
        return visited;
    }
}
