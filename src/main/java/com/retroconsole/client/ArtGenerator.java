package com.retroconsole.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.retroconsole.client.library.GameSystem;
import net.minecraft.util.FastColor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Генерирует PNG-обложки консолей в config/retroconsole/art/, если их нет. */
public final class ArtGenerator {

    public static final Path ART_DIR = Paths.get("config/retroconsole/art");
    private static final int W = 168;
    private static final int H = 62;

    private ArtGenerator() {}

    public static void ensure(GameSystem system) {
        if (system == null) return;
        Path png = ART_DIR.resolve(system.folder + ".png");
        if (Files.exists(png)) return;
        try {
            Files.createDirectories(ART_DIR);
            int c = system.color;
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            try (NativeImage img = new NativeImage(W, H, false)) {
                for (int y = 0; y < H; y++) {
                    float t = (float) y / Math.max(1, H - 1);
                    int rr = clamp((int) (r * (1.0f - t * 0.55f)));
                    int gg = clamp((int) (g * (1.0f - t * 0.55f)));
                    int bb = clamp((int) (b * (1.0f - t * 0.55f)));
                    int pixel = FastColor.ABGR32.color(255, bb, gg, rr);
                    for (int x = 0; x < W; x++) {
                        img.setPixelRGBA(x, y, pixel);
                    }
                }
                img.writeToFile(png.toFile());
            }
            TextureCache.invalidate(png);
        } catch (Exception ignored) {}
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
