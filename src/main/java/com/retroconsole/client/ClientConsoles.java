package com.retroconsole.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side console screens.
 *
 * "Last frame wins" architecture:
 *
 * - submitFrame() — called from the NETWORK thread. Frame arrives already in ABGR
 *   (RetroFramePacket.decompressFrameAbgr) and goes into the PENDING slot.
 *   If render did not pick up the previous frame, it is silently overwritten.
 *
 * - uploadPendingFrame() — render thread only (from inside getScreen()).
 *   Takes the frame from the slot and uploads to texture with one bulk copy
 *   + glTexSubImage2D. Frame array moves into entry ownership (lastAbgr)
 *   WITHOUT copying: submitFrame brings a new array each time and nobody
 *   mutates it afterward.
 */
public final class ClientConsoles {
    private ClientConsoles() {}

    /** Frame waiting for upload. Pixels already in ABGR (GL RGBA little-endian). */
    private record PendingFrame(int[] abgr, int width, int height) {}

    /** Console screen: texture + persistent staging buffer. Lives until dispose() or resize. */
    public static final class ScreenEntry {
        private final DynamicTexture tex;
        private final ResourceLocation id;
        private final int width;
        private final int height;
        private final IntBuffer staging; // direct memory, reused every frame
        private int[] lastAbgr;

        private ScreenEntry(BlockPos pos, int width, int height) {
            this.width = width;
            this.height = height;
            this.tex = new DynamicTexture(width, height, true);
            this.id = Minecraft.getInstance().getTextureManager()
                    .register("retro_screen_" + pos.asLong(), tex);
            this.staging = MemoryUtil.memAllocInt(width * height);
        }

        private void close() {
            Minecraft.getInstance().getTextureManager().release(id);
            MemoryUtil.memFree(staging);
        }

        public DynamicTexture tex() { return tex; }
        public ResourceLocation id() { return id; }
        public int width() { return width; }
        public int height() { return height; }
        public int[] lastAbgr() { return lastAbgr; }
    }

    /** PENDING written from network thread, read from render — must be concurrent. */
    private static final Map<BlockPos, PendingFrame> PENDING = new ConcurrentHashMap<>();
    /** SCREENS touched only from render thread; concurrent map is cheap insurance. */
    private static final Map<BlockPos, ScreenEntry> SCREENS = new ConcurrentHashMap<>();

    private static final Map<BlockPos, int[]> GRID_CACHE = new HashMap<>();
    /**
     * BUGFIX: cache was cleared by ++frameCounter % 60 — a call counter, not frames:
     * N screen blocks = N increments per frame, cache reset N times too often. Now time-based.
     */
    private static final long GRID_CACHE_TTL_NS = 2_000_000_000L;
    private static long gridCacheClearedNs = System.nanoTime();

    /**
     * Accept a frame from the network thread. Array is already ABGR and ownership
     * transfers to ClientConsoles — caller must not touch it afterward.
     * Unshown previous frame is silently overwritten.
     */
    public static void submitFrame(BlockPos pos, int[] abgr, int width, int height) {
        if (abgr == null || width <= 0 || height <= 0) return;
        if (abgr.length < width * height) return;
        PENDING.put(pos.immutable(), new PendingFrame(abgr, width, height));
    }

    /**
     * Get console screen. Uploads a fresh frame before return if one is pending.
     * Repeated calls in the same render frame are free (PENDING already empty).
     */
    public static ScreenEntry getScreen(BlockPos consolePos) {
        if (consolePos == null) return null;
        BlockPos pos = consolePos.immutable();
        uploadPendingFrame(pos);
        return SCREENS.get(pos);
    }

    /** Without frame upload — callable from any thread (thumbnails on exit). */
    public static ScreenEntry peekScreen(BlockPos consolePos) {
        if (consolePos == null) return null;
        return SCREENS.get(consolePos.immutable());
    }

    /** Render thread only. Takes latest frame from slot and uploads to texture. */
    private static void uploadPendingFrame(BlockPos pos) {
        if (!RenderSystem.isOnRenderThread()) return;

        PendingFrame f = PENDING.remove(pos);
        if (f == null) return;

        ScreenEntry entry = SCREENS.get(pos);
        if (entry != null && (entry.width != f.width() || entry.height != f.height())) {
            entry.close();
            SCREENS.remove(pos);
            entry = null;
        }
        if (entry == null) {
            entry = new ScreenEntry(pos, f.width(), f.height());
            SCREENS.put(pos, entry);
        }

        int n = f.width() * f.height();
        entry.staging.clear();
        entry.staging.put(f.abgr(), 0, n);
        entry.staging.flip();
        // OPTIMIZATION: Arrays.copyOf(f.abgr(), n) here was ~11 MB extra alloc per frame
        // at 1920x1440. Array belongs solely to PendingFrame and is not mutated — take as-is.
        entry.lastAbgr = f.abgr();

        GlStateManager._bindTexture(entry.tex.getId());
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._texSubImage2D(
                GL11.GL_TEXTURE_2D, 0, 0, 0,
                f.width(), f.height(),
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                MemoryUtil.memAddress(entry.staging));
    }

    /** Call from render thread (via enqueueWork from stop-packet handler). */
    public static void dispose(BlockPos pos) {
        pos = pos.immutable();
        PENDING.remove(pos);
        ScreenEntry entry = SCREENS.remove(pos);
        if (entry != null) {
            entry.close();
        }
        GRID_CACHE.clear();
    }

    // ------------------------------------------------------------------
    // Multi-block TV grid
    // ------------------------------------------------------------------

    public static int[] getGridInfo(ScreenBlockEntity be) {
        BlockPos pos = be.getBlockPos().immutable();
        long now = System.nanoTime();
        if (now - gridCacheClearedNs >= GRID_CACHE_TTL_NS) {
            GRID_CACHE.clear();
            gridCacheClearedNs = now;
        }
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