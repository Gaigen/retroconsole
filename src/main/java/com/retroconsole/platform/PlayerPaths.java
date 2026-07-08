package com.retroconsole.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player save paths.
 *
 * <p><b>Shared {@code system/}</b> — BIOS, PPSSPP assets, PCSX2 shader cache.
 * Always passed as {@code GET_SYSTEM_DIRECTORY} (works on Windows without symlinks).
 *
 * <p><b>Per-player {@code saves/players/{uuid}/}</b> — battery saves ({@code .srm}).
 *
 * <p><b>Per-player {@code saves/players/{uuid}/pcsx2/memcards/}</b> — PS2 memory cards
 * on disk; before {@code retro_load_game} copied into live {@code system/pcsx2/memcards/}
 * via {@link Pcsx2MemcardSync}, and copied back on shutdown.
 */
public record PlayerPaths(UUID playerId, Path saveDir, Path systemDir, Path pcsx2MemcardsDir) {

    private static final Logger LOGGER = LoggerFactory.getLogger("PlayerPaths");
    private static final Set<UUID> LOGGED_PLAYERS = ConcurrentHashMap.newKeySet();

    public static final UUID SHARED_OWNER =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    public static PlayerPaths forPlayer(UUID playerId) {
        UUID id = playerId != null ? playerId : SHARED_OWNER;
        Path playerRoot = RetroConsolePaths.saveDir()
                .resolve("players")
                .resolve(id.toString())
                .toAbsolutePath()
                .normalize();

        Path pSave = mkdirs(playerRoot);
        Path memcards = mkdirs(playerRoot.resolve("pcsx2").resolve("memcards"));

        if (LOGGED_PLAYERS.add(id)) {
            LOGGER.info("Player {}: saves -> {}, PS2 memcards stash -> {}, system -> (shared) {}",
                    id, pSave, memcards, RetroConsolePaths.systemDir());
        }

        return new PlayerPaths(id, pSave, RetroConsolePaths.systemDir(), memcards);
    }

    public static PlayerPaths shared() {
        Path live = RetroConsolePaths.pcsx2MemcardsDir();
        return new PlayerPaths(SHARED_OWNER, RetroConsolePaths.saveDir(), RetroConsolePaths.systemDir(), live);
    }

    public boolean isPerPlayer() {
        return playerId != null && !SHARED_OWNER.equals(playerId);
    }

    private static Path mkdirs(Path p) {
        try {
            Files.createDirectories(p);
        } catch (Exception ex) {
            LOGGER.error("Failed to create directory {}: {}", p, ex.getMessage());
        }
        return p;
    }
}
