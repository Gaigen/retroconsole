package com.retroconsole.library;

import com.retroconsole.RetroConsole;
import com.retroconsole.network.RetroArtPacket;
import com.retroconsole.platform.RetroConsolePaths;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Console cover art at art/{folder}.png (server-safe; no client NativeImage). */
public final class ArtFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Art");
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

    public static List<RetroArtPacket.ArtEntry> loadPacketEntries(Collection<String> folders) {
        Path root = RetroConsolePaths.artDir().toAbsolutePath().normalize();
        List<RetroArtPacket.ArtEntry> out = new ArrayList<>();
        for (String folder : folders) {
            if (!safeFolder(folder)) continue;
            Path png = root.resolve(folder + ".png").normalize();
            if (!png.startsWith(root) || !Files.isRegularFile(png)) continue;
            try {
                long size = Files.size(png);
                if (size <= 0 || size > RetroArtPacket.MAX_PNG_BYTES) {
                    LOGGER.warn("Skip art {}: size {} bytes", png.getFileName(), size);
                    continue;
                }
                byte[] data = Files.readAllBytes(png);
                if (looksLikePng(data)) {
                    out.add(new RetroArtPacket.ArtEntry(folder, data));
                }
            } catch (IOException ex) {
                LOGGER.warn("Failed to read art {}: {}", png, ex.getMessage());
            }
        }
        return out;
    }

    private static boolean safeFolder(String folder) {
        return folder != null && !folder.isEmpty() && folder.matches("[a-zA-Z0-9_\\-]+");
    }

    private static boolean looksLikePng(byte[] data) {
        return data.length >= 8
                && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    @EventBusSubscriber(modid = RetroConsole.MOD_ID)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent
        public static void onCommonSetup(FMLCommonSetupEvent event) {
            event.enqueueWork(() -> ensureDefaults(RetroConsolePaths.artDir()));
        }
    }
}
