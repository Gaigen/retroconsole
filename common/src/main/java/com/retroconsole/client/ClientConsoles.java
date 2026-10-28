package com.retroconsole.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

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
 *   Takes the frame from the slot, copies it into the DynamicTexture's backing
 *   image and re-uploads the texture. Frame array moves into entry ownership
 *   (lastAbgr) WITHOUT copying: submitFrame brings a new array each time and
 *   nobody mutates it afterward.
 */
public final class ClientConsoles {
    private ClientConsoles() {}

    /** Frame waiting for upload. Pixels already in ABGR (GL RGBA little-endian). */
    private record PendingFrame(int[] abgr, int width, int height) {}

    /** DynamicTexture whose sampler can be flipped between smooth and crisp. */
    private static final class ScreenTexture extends DynamicTexture {
        ScreenTexture(String label, int width, int height) {
            super(label, width, height, true);
        }

        void setLinear(boolean linear) {
            this.sampler = RenderSystem.getSamplerCache()
                    .getRepeat(linear ? FilterMode.LINEAR : FilterMode.NEAREST);
        }
    }

    /** Console screen: texture + persistent staging buffer. Lives until dispose() or resize. */
    public static final class ScreenEntry {
        private final ScreenTexture tex;
        private final Identifier id;
        private final int width;
        private final int height;
        private int[] lastAbgr;
        /** Current filter state, so setLinear() only touches the sampler when it changes. */
        private boolean linear;

        private ScreenEntry(UUID consoleId, int width, int height) {
            this.width = width;
            this.height = height;
            this.tex = new ScreenTexture("retroconsole_screen_" + consoleId, width, height);
            this.id = Identifier.fromNamespaceAndPath("retroconsole", "screen/" + consoleId);
            Minecraft.getInstance().getTextureManager().register(id, tex);
        }

        private void close() {
            Minecraft.getInstance().getTextureManager().release(id);
        }

        public DynamicTexture tex() { return tex; }
        public Identifier id() { return id; }
        public int width() { return width; }
        public int height() { return height; }
        public int[] lastAbgr() { return lastAbgr; }

        /**
         * Pick the sampling filter for how the texture is drawn right now.
         *
         * <p>Nearest is only correct at whole-number scales: at 1.83x (768x576 in a
         * 1080p window) it turns a 1px glyph stem into an uneven mix of 1px and 2px
         * runs, so small text stops being readable. Integer scales stay crisp pixel
         * art, everything else is smoothed. Render thread only; no-op when unchanged.
         */
        public void setLinear(boolean linear) {
            if (linear == this.linear) return;
            this.linear = linear;
            tex.setLinear(linear);
        }
    }

    private static final Map<UUID, PendingFrame> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, ScreenEntry> SCREENS = new ConcurrentHashMap<>();

    /**
     * Wall-clock time of the last server STOP_CONSOLE per console.
     *
     * <p>Lets the viewer tell "nothing has arrived yet because the core is still
     * booting" from "nothing arrives because the console is dead". A screen opened
     * after this stamp is a fresh start; a screen opened before it watched the
     * console stop and must not pretend it is booting.
     */
    private static final Map<UUID, Long> STOPPED_AT = new ConcurrentHashMap<>();

    public static void submitFrame(UUID consoleId, int[] abgr, int width, int height) {
        if (consoleId == null || abgr == null || width <= 0 || height <= 0) return;
        if (abgr.length < width * height) return;
        STOPPED_AT.remove(consoleId);
        PENDING.put(consoleId, new PendingFrame(abgr, width, height));
    }

    /** Epoch millis when this console was stopped by the server, or -1 if it never was. */
    public static long stoppedAt(UUID consoleId) {
        if (consoleId == null) return -1L;
        Long stopped = STOPPED_AT.get(consoleId);
        return stopped != null ? stopped : -1L;
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
        entry.lastAbgr = f.abgr();

        // The texture keeps its own NativeImage; write the frame straight into it
        // (ABGR ints are RGBA8 bytes in little-endian order) and re-upload.
        NativeImage img = entry.tex.getPixels();
        MemoryUtil.memIntBuffer(img.getPointer(), n).put(f.abgr(), 0, n);
        entry.tex.upload();
    }

    public static void dispose(UUID consoleId) {
        if (consoleId == null) return;
        PENDING.remove(consoleId);
        STOPPED_AT.put(consoleId, System.currentTimeMillis());
        ScreenEntry entry = SCREENS.remove(consoleId);
        if (entry != null) {
            entry.close();
        }
    }
}
