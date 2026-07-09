package com.retroconsole.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Disk PNG -> GUI texture. Loaded once, not every frame. */
public final class TextureCache {

    public record Tex(ResourceLocation location, int width, int height) {}

    private static final Map<String, Tex> CACHE = new HashMap<>();
    private static int counter;

    /** null if the file is missing or unreadable. */
    public static Tex get(Path png) {
        String key = png.toAbsolutePath().toString();
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Tex tex = null;
        if (Files.exists(png)) {
            try (InputStream in = Files.newInputStream(png)) {
                NativeImage img = NativeImage.read(in);
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                        "retroconsole", "dyn/" + (counter++));
                Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
                tex = new Tex(rl, img.getWidth(), img.getHeight());
            } catch (Exception ignored) {}
        }
        CACHE.put(key, tex);
        return tex;
    }

    /** PNG from memory (e.g. from server). null if data is empty or corrupt. */
    public static Tex getFromBytes(String key, byte[] png) {
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Tex tex = null;
        if (png != null && png.length > 0) {
            try (InputStream in = new ByteArrayInputStream(png)) {
                NativeImage img = NativeImage.read(in);
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                        "retroconsole", "dyn/" + (counter++));
                Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
                tex = new Tex(rl, img.getWidth(), img.getHeight());
            } catch (Exception ignored) {}
        }
        CACHE.put(key, tex);
        return tex;
    }

    /** Evict cache entry (call when opening a screen to pick up fresh thumbnails). */
    public static void invalidate(String key) { CACHE.remove(key); }

    /** Evict cache entry by disk path. */
    public static void invalidate(Path png) { CACHE.remove(png.toAbsolutePath().toString()); }
}