package com.retroconsole.library;

import com.retroconsole.RetroConsole;
import com.retroconsole.platform.RetroConsolePaths;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** PNG-обложки art/{folder}.png — работает и на сервере (без client-only NativeImage). */
public final class ArtFiles {

    private static final int W = 168;
    private static final int H = 62;

    private ArtFiles() {}

    public static void ensureDefaults(Path artDir) {
        GameSystem.reload();
        ensureForSystems(artDir, GameSystem.all());
    }

    public static void ensureForSystems(Path artDir, List<GameSystem> systems) {
        try {
            Files.createDirectories(artDir);
        } catch (IOException ignored) {}
        for (GameSystem s : systems) {
            if (s != GameSystem.OTHER) ensure(artDir, s);
        }
    }

    public static void ensure(Path artDir, GameSystem system) {
        if (system == null) return;
        Path png = artDir.resolve(system.folder + ".png");
        if (Files.exists(png)) return;
        try {
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            int c = system.color;
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            for (int y = 0; y < H; y++) {
                float t = (float) y / Math.max(1, H - 1);
                int rr = clamp((int) (r * (1.0f - t * 0.55f)));
                int gg = clamp((int) (g * (1.0f - t * 0.55f)));
                int bb = clamp((int) (b * (1.0f - t * 0.55f)));
                int argb = (0xFF << 24) | (rr << 16) | (gg << 8) | bb;
                for (int x = 0; x < W; x++) {
                    img.setRGB(x, y, argb);
                }
            }
            Files.createDirectories(png.getParent());
            ImageIO.write(img, "png", png.toFile());
        } catch (Exception ignored) {}
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    @EventBusSubscriber(modid = RetroConsole.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void onCommonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() -> ensureDefaults(RetroConsolePaths.artDir()));
        }
    }
}
