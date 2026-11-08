package com.retroconsole.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Windows directory junctions (reparse points) used to hand cores a short path
 * to data that physically lives in a deep directory tree.
 *
 * <p>Why this exists: some libretro cores (Citra being the prime example) build
 * very long save paths on top of the directory we return from
 * {@code GET_SAVE_DIRECTORY}, and their native file code calls Win32 without the
 * {@code \\?\} prefix. Once the resulting string crosses the 248-character
 * directory limit, {@code CreateDirectory} fails and the game cannot bootstrap
 * its save. A junction sidesteps this: Win32 enforces MAX_PATH on the string the
 * caller passes, while the I/O manager resolves reparse points without that
 * limit — so a short junction path transparently reaches the long real path.
 *
 * <p>Junctions are created with {@code mklink /J}, which needs no administrator
 * rights (unlike symlinks). On non-Windows platforms every operation is a no-op
 * that reports unsupported, so callers fall back to the real directory.
 */
public final class DirectoryJunction {

    private static final Logger LOGGER = LoggerFactory.getLogger("DirectoryJunction");

    private DirectoryJunction() {}

    /** Junctions only exist on Windows; callers use this to decide whether to bother. */
    public static boolean isSupported() {
        return OsUtil.isWindows();
    }

    /**
     * Ensure {@code link} is a directory junction pointing at {@code target}.
     * Idempotent: an existing junction already aimed at {@code target} is kept,
     * a stale/wrong one is replaced.
     *
     * @return {@code true} if {@code link} now resolves to {@code target} as a junction
     */
    public static boolean ensure(Path link, Path target) {
        if (!isSupported()) return false;
        try {
            if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (pointsTo(link, target)) return true;
                // Wrong or leftover link: drop just the reparse point, never the target.
                remove(link);
            }
            Files.createDirectories(link.getParent());
            if (create(link, target) && pointsTo(link, target)) {
                LOGGER.info("Junction {} -> {}", link, target);
                return true;
            }
            LOGGER.warn("Junction creation failed for {} -> {}", link, target);
            return false;
        } catch (Exception ex) {
            LOGGER.warn("Could not prepare junction {} -> {}: {}", link, target, ex.getMessage());
            return false;
        }
    }

    private static boolean create(Path link, Path target) {
        try {
            Process p = new ProcessBuilder(
                    "cmd.exe", "/c", "mklink", "/J",
                    link.toAbsolutePath().toString(),
                    target.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            return code == 0 && Files.isDirectory(link);
        } catch (Exception ex) {
            LOGGER.debug("mklink /J failed: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Remove a junction (or empty directory) without ever deleting what it points
     * to. {@code rmdir} on a reparse point drops only the link.
     */
    public static boolean remove(Path link) {
        if (!isSupported()) return false;
        try {
            if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return true;
            Process p = new ProcessBuilder("cmd.exe", "/c", "rmdir", link.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            int code = p.waitFor();
            return code == 0 || !Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ex) {
            LOGGER.debug("rmdir junction failed for {}: {}", link, ex.getMessage());
            return false;
        }
    }

    /**
     * True when {@code link} resolves to {@code target}. A junction (or symlink, or
     * bind) all resolve through {@link Path#toRealPath()} to the same physical
     * directory, so path equality is the reliable test — the Windows reparse-tag
     * attribute is not consistently available across JDK builds and filesystems.
     */
    public static boolean pointsTo(Path link, Path target) {
        try {
            if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
            return link.toRealPath().equals(target.toRealPath());
        } catch (IOException ex) {
            return false;
        }
    }
}
