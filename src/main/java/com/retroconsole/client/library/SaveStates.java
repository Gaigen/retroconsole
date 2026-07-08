package com.retroconsole.client.library;

import com.retroconsole.platform.RetroConsolePaths;
import com.retroconsole.platform.SaveFiles;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Пути к сейв-стейтам и миниатюрам. Слоты F5/F6 — через {@link SaveFiles}, автосейв — по romId. */
public final class SaveStates {

    private static final int DEFAULT_SLOT = 0;

    private static UUID cachedPlayerId;
    private static Path cachedSaveRoot;

    private SaveStates() {}

    /** Каталог сейвов текущего локального игрока (как на сервере в SP). */
    public static Path saveRoot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            return saveRoot(mc.player.getUUID());
        }
        return RetroConsolePaths.saveDir();
    }

    public static Path saveRoot(UUID playerId) {
        if (playerId == null) return RetroConsolePaths.saveDir();
        if (playerId.equals(cachedPlayerId) && cachedSaveRoot != null) {
            return cachedSaveRoot;
        }
        cachedPlayerId = playerId;
        cachedSaveRoot = RetroConsolePaths.saveDir()
                .resolve("players")
                .resolve(playerId.toString());
        return cachedSaveRoot;
    }

    private static Path romPath(String romId) {
        return Path.of(romId.replace('\\', '/'));
    }

    /** Ручной сейв (F5/F6), слот 0 — совпадает с {@link SaveFiles}. */
    public static Path stateFor(String romId) {
        return stateFor(romId, DEFAULT_SLOT);
    }

    public static Path stateFor(String romId, int slot) {
        return SaveFiles.saveStatePath(saveRoot(), romPath(romId), slot);
    }

    /** Автосейв при закрытии TvScreen. */
    public static Path autoFor(String romId) {
        return SaveFiles.autoStatePath(saveRoot(), romId);
    }

    /** Миниатюра для библиотеки — локально у клиента, per-player. */
    public static Path thumbFor(String romId) {
        return ClientPlayerData.thumbsDir().resolve(romId.replace('\\', '/') + ".png");
    }

    public static boolean hasSave(String romId) {
        return Files.exists(stateFor(romId)) || Files.exists(autoFor(romId));
    }

    /** Время последнего сохранения (epoch сек) или 0. */
    public static long saveTime(String romId) {
        long t = 0;
        for (Path p : new Path[]{stateFor(romId), autoFor(romId)}) {
            try {
                if (Files.exists(p))
                    t = Math.max(t, Files.getLastModifiedTime(p).toMillis() / 1000);
            } catch (Exception ignored) {}
        }
        return t;
    }
}
