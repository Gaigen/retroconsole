package com.retroconsole.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side console screens keyed by stable console UUID.
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
        private final IntBuffer staging;
        private int[] lastAbgr;

        private ScreenEntry(UUID consoleId, int width, int height) {
            this.width = width;
            this.height = height;
            this.tex = new DynamicTexture(width, height, true);
            this.id = Minecraft.getInstance().getTextureManager()
                    .register("retro_screen_" + consoleId, tex);
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

    private static final Map<UUID, PendingFrame> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, ScreenEntry> SCREENS = new ConcurrentHashMap<>();

    public static void submitFrame(UUID consoleId, int[] abgr, int width, int height) {
        if (consoleId == null || abgr == null || width <= 0 || height <= 0) return;
        if (abgr.length < width * height) return;
        PENDING.put(consoleId, new PendingFrame(abgr, width, height));
    }

    public static ScreenEntry getScreen(UUID consoleId) {
        if (consoleId == null) return null;
        uploadPendingFrame(consoleId);
        return SCREENS.get(consoleId);
    }

    public static ScreenEntry peekScreen(UUID consoleId) {
        if (consoleId == null) return null;
        return SCREENS.get(consoleId);
    }

    private static void uploadPendingFrame(UUID consoleId) {
        if (!RenderSystem.isOnRenderThread()) return;

        PendingFrame f = PENDING.remove(consoleId);
        if (f == null) return;

        ScreenEntry entry = SCREENS.get(consoleId);
        if (entry != null && (entry.width != f.width() || entry.height != f.height())) {
            entry.close();
            SCREENS.remove(consoleId);
            entry = null;
        }
        if (entry == null) {
            entry = new ScreenEntry(consoleId, f.width(), f.height());
            SCREENS.put(consoleId, entry);
        }

        int n = f.width() * f.height();
        entry.staging.clear();
        entry.staging.put(f.abgr(), 0, n);
        entry.staging.flip();
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

    public static void dispose(UUID consoleId) {
        if (consoleId == null) return;
        PENDING.remove(consoleId);
        ScreenEntry entry = SCREENS.remove(consoleId);
        if (entry != null) {
            entry.close();
        }
    }
}
