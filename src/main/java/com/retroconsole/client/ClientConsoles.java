package com.retroconsole.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.Map;
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
    }
}