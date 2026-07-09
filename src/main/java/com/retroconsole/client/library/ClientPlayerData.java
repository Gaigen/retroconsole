package com.retroconsole.client.library;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Client-local per-player data: stats, ui, thumbs. */
public final class ClientPlayerData {

    private static final Path ROOT = Paths.get("config/retroconsole/players");
    private static final Path LEGACY_STATS = Paths.get("config/retroconsole/stats.properties");
    private static final Path LEGACY_UI = Paths.get("config/retroconsole/ui.properties");
    private static final Path LEGACY_THUMBS = Paths.get("config/retroconsole/thumbs");

    private static UUID cachedId;
    private static Path cachedDir;

    private ClientPlayerData() {}

    public static Path dir() {
        LocalPlayer player = Minecraft.getInstance().player;
        UUID id = player != null ? player.getUUID() : new UUID(0, 0);
        if (id.equals(cachedId) && cachedDir != null) return cachedDir;
        cachedId = id;
        cachedDir = ROOT.resolve(id.toString());
        return cachedDir;
    }

    public static Path statsFile() {
        Path file = dir().resolve("stats.properties");
        migrateFile(LEGACY_STATS, file);
        return file;
    }

    public static Path uiFile() {
        Path file = dir().resolve("ui.properties");
        migrateFile(LEGACY_UI, file);
        return file;
    }

    public static Path thumbsDir() {
        Path dir = dir().resolve("thumbs");
        migrateDir(LEGACY_THUMBS, dir);
        return dir;
    }

    public static void resetCache() {
        cachedId = null;
        cachedDir = null;
    }

    private static void migrateFile(Path legacy, Path target) {
        try {
            if (Files.exists(target) || !Files.exists(legacy)) return;
            Files.createDirectories(target.getParent());
            Files.copy(legacy, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }

    private static void migrateDir(Path legacy, Path target) {
        try {
            if (!Files.isDirectory(legacy)) return;
            Files.createDirectories(target);
            try (var stream = Files.list(legacy)) {
                stream.filter(Files::isRegularFile).forEach(src -> {
                    Path dst = target.resolve(src.getFileName().toString());
                    if (Files.exists(dst)) return;
                    try {
                        Files.copy(src, dst);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
