package com.retroconsole.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Hands the Citra (3DS) core a short save directory.
 *
 * <p>Citra builds its user tree as {@code <saveDir>/Citra/sdmc/Nintendo 3DS/<id0>/
 * <id1>/title/<tid_high>/<tid_low>/data/<index>/save_data} and its native file code
 * calls Win32 without {@code \\?\}. Fed straight from
 * {@code saves/players/<uuid>} the resulting string crosses the 248-character
 * directory limit, {@code CreateDirectory} fails, and the game hangs on
 * "Unable to rebuild save data."
 *
 * <p>The fix mirrors what {@link Pcsx2MemcardSync} does for its shared BIOS: create
 * a short directory junction under {@code system/.sessions/} that points at the real
 * per-player directory, and give the core the junction path instead. The path string
 * the core sees stays short (Win32 enforces MAX_PATH on the string, not the resolved
 * target), while the data physically lands in the normal, persistent location — no
 * copying, no migration. On non-Windows platforms (PATH_MAX is 4096 there) this is a
 * no-op and callers fall back to the real directory.
 */
public final class CitraSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("CitraSession");

    private CitraSession() {}

    /**
     * @return a short save directory to hand the core, or {@code null} to keep using
     *         {@code paths.saveDir()} unchanged (unsupported OS or junction failure).
     */
    public static Path shortSaveDir(PlayerPaths paths) {
        if (paths == null || !DirectoryJunction.isSupported()) {
            return null;
        }
        Path target = paths.saveDir().toAbsolutePath().normalize();
        Path link = RetroConsolePaths.systemDir()
                .resolve(".sessions")
                .resolve("citra-" + shortId(paths.playerId()))
                .toAbsolutePath()
                .normalize();
        if (!DirectoryJunction.ensure(link, target)) {
            LOGGER.warn("Citra short-path junction unavailable; falling back to {}", target);
            return null;
        }
        LOGGER.info("Citra save dir -> short junction {} -> {} (avoids MAX_PATH)", link, target);
        return link;
    }

    /** First 8 hex digits of the player UUID, without dashes — stable per player. */
    private static String shortId(UUID id) {
        if (id == null) return "shared";
        String hex = id.toString().replace("-", "");
        return hex.length() >= 8 ? hex.substring(0, 8) : hex;
    }
}
