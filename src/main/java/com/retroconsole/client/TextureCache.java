package com.retroconsole.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** PNG с диска -> GUI-текстура. Грузим один раз, а не каждый кадр. */
public final class TextureCache {

    public record Tex(ResourceLocation location, int width, int height) {}

    private static final Map<String, Tex> CACHE = new HashMap<>();
    private static int counter;

    /** null, если файла нет или он не читается. */
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

    /** Сброс записи (вызываем при открытии экрана, чтобы подхватить свежие миниатюры). */
    public static void invalidate(Path png) { CACHE.remove(png.toAbsolutePath().toString()); }
}