package com.retroconsole.client.bezel;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Loads and draws client-local bezel images into TvScreen letterbox gutters. */
public final class TvBezelRenderer {

    private static final Map<String, SideTexture> CACHE = new HashMap<>();

    public record Preview(ResourceLocation id, int w, int h) {}

    private record SideTexture(ResourceLocation id, DynamicTexture staticTex, GifTexture gif, int w, int h) {
        void tick(long now) { if (gif != null) gif.tick(now); }
        void release(Minecraft mc) {
            if (staticTex != null) staticTex.close();
            if (gif != null) { gif.closeFrames(); mc.getTextureManager().release(id); }
            else if (id != null) mc.getTextureManager().release(id);
        }
    }

    private TvBezelRenderer() {}

    public static void invalidateAll() {
        Minecraft mc = Minecraft.getInstance();
        for (SideTexture t : CACHE.values()) t.release(mc);
        CACHE.clear();
    }

    /** Live preview for settings GUI — same cache as render, GIF animates. */
    public static Preview preview(Path file, long nowMs) {
        if (file == null) return null;
        SideTexture t = load(file);
        if (t == null) return null;
        t.tick(nowMs);
        return new Preview(t.id, t.w, t.h);
    }

    public static void render(GuiGraphics g, int screenW, int screenH, int barHeight,
                              int frameX, int frameY, int frameW, int frameH, long nowMs) {
        if (!TvBezelPrefs.enabled()) return;
        TvBezelPrefs.bezelDir();

        int gutterLeft = frameX;
        int gutterRight = screenW - (frameX + frameW);
        int topH = screenH - barHeight;
        if (gutterLeft < 8 && gutterRight < 8) return;

        float alpha = TvBezelPrefs.opacity();

        Path left = TvBezelPrefs.leftPath();
        Path right = TvBezelPrefs.rightPath();

        if (gutterLeft >= 8 && left != null) {
            drawSide(g, left, 0, 0, gutterLeft, topH, alpha, nowMs);
        }
        if (gutterRight >= 8 && right != null) {
            drawSide(g, right, frameX + frameW, 0, gutterRight, topH, alpha, nowMs);
        }
    }

    private static void drawSide(GuiGraphics g, Path file, int x, int y, int w, int h,
                                 float alpha, long nowMs) {
        SideTexture tex = load(file);
        if (tex == null) return;
        tex.tick(nowMs);
        int drawW = w, drawH = h;
        switch (TvBezelPrefs.fitMode()) {
            case FIT -> {
                float scale = Math.min((float) w / tex.w, (float) h / tex.h);
                drawW = Math.max(1, Math.round(tex.w * scale));
                drawH = Math.max(1, Math.round(tex.h * scale));
            }
            case FILL -> {
                float scale = Math.max((float) w / tex.w, (float) h / tex.h);
                drawW = Math.max(1, Math.round(tex.w * scale));
                drawH = Math.max(1, Math.round(tex.h * scale));
            }
            case STRETCH -> { /* use panel w/h */ }
        }
        int dx = x + (w - drawW) / 2;
        int dy = y + (h - drawH) / 2;
        g.enableScissor(x, y, x + w, y + h);
        g.setColor(1f, 1f, 1f, alpha);
        g.blit(tex.id, dx, dy, drawW, drawH, 0f, 0f, tex.w, tex.h, tex.w, tex.h);
        g.setColor(1f, 1f, 1f, 1f);
        g.disableScissor();
    }

    private static SideTexture load(Path file) {
        String key = file.toAbsolutePath().normalize().toString();
        SideTexture cached = CACHE.get(key);
        if (cached != null) return cached;

        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                "retroconsole", "bezel/" + Math.abs(key.hashCode()));

        try {
            if (lower.endsWith(".gif")) {
                GifTexture gif = new GifTexture(id, file);
                mc.getTextureManager().register(id, gif);
                SideTexture side = new SideTexture(id, null, gif, gif.width(), gif.height());
                CACHE.put(key, side);
                return side;
            }
            try (InputStream in = Files.newInputStream(file);
                 NativeImage img = NativeImage.read(in)) {
                DynamicTexture dyn = new DynamicTexture(img);
                mc.getTextureManager().register(id, dyn);
                SideTexture side = new SideTexture(id, dyn, null, img.getWidth(), img.getHeight());
                CACHE.put(key, side);
                return side;
            }
        } catch (IOException e) {
            return null;
        }
    }
}
