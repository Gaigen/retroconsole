package com.retroconsole.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * PCSX2 only reads/writes {@code system/pcsx2/memcards/}. Per-player cards are kept
 * under {@code saves/players/{uuid}/pcsx2/memcards/} and synced around each session.
 */
public final class Pcsx2MemcardSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("Pcsx2MemcardSync");

    private static final Object LOCK = new Object();
    private static UUID activeOwner;

    private Pcsx2MemcardSync() {}

    /** Copy player stash → live memcards before {@code retro_load_game}. */
    public static void install(PlayerPaths paths) {
        if (!paths.isPerPlayer()) return;
        Path live = RetroConsolePaths.pcsx2MemcardsDir();
        Path stash = paths.pcsx2MemcardsDir();
        synchronized (LOCK) {
            if (activeOwner != null && !activeOwner.equals(paths.playerId())) {
                LOGGER.warn("PS2 memcards in use by {}; {} may see wrong saves",
                        activeOwner, paths.playerId());
            }
            activeOwner = paths.playerId();
            mkdirs(live);
            mkdirs(stash);
            int n = copyMemcards(stash, live);
            LOGGER.info("PS2 memcards install for {}: {} file(s) from {} -> {}",
                    paths.playerId(), n, stash, live);
        }
    }

    /** Copy live memcards → player stash after {@code retro_unload_game}. */
    public static void export(PlayerPaths paths) {
        if (!paths.isPerPlayer()) return;
        Path live = RetroConsolePaths.pcsx2MemcardsDir();
        Path stash = paths.pcsx2MemcardsDir();
        synchronized (LOCK) {
            if (activeOwner != null && !activeOwner.equals(paths.playerId())) {
                LOGGER.warn("Skip PS2 memcard export for {} (active owner is {})",
                        paths.playerId(), activeOwner);
                return;
            }
            mkdirs(stash);
            int n = copyMemcards(live, stash);
            activeOwner = null;
            LOGGER.info("PS2 memcards export for {}: {} file(s) from {} -> {}",
                    paths.playerId(), n, live, stash);
        }
    }

    private static int copyMemcards(Path from, Path to) {
        if (!Files.isDirectory(from)) return 0;
        mkdirs(to);
        int count = 0;
        try (Stream<Path> files = Files.list(from)) {
            for (Path src : files.filter(Pcsx2MemcardSync::isMemcardFile).toList()) {
                Path dst = to.resolve(src.getFileName());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        } catch (IOException ex) {
            LOGGER.warn("Failed to copy memcards {} -> {}: {}", from, to, ex.getMessage());
        }
        return count;
    }

    private static boolean isMemcardFile(Path p) {
        if (!Files.isRegularFile(p)) return false;
        String name = p.getFileName().toString().toLowerCase();
        return name.startsWith("mcd") && (name.endsWith(".ps2") || name.endsWith(".ps2.tmp"));
    }

    private static void mkdirs(Path p) {
        try {
            Files.createDirectories(p);
        } catch (IOException ex) {
            LOGGER.warn("Failed to create {}: {}", p, ex.getMessage());
        }
    }
}
