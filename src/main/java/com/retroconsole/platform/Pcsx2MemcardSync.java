package com.retroconsole.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * PCSX2 reads/writes {@code <systemDir>/pcsx2/memcards/}. Each session gets a
 * private system root under {@code system/.sessions/ps2-N/} (shared BIOS via
 * directory junction).
 *
 * <p>Default up to {@code 8} concurrent PS2 sessions (VEH wrap gates foreign
 * Fastmem). Override: {@code -Dretroconsole.maxPcsx2Sessions=N}.
 */
public final class Pcsx2MemcardSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("Pcsx2MemcardSync");
    private static final Object LOCK = new Object();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final Map<Integer, Session> LIVE = new ConcurrentHashMap<>();

    /** Soft cap — VEH wrap table is also 8 slots. */
    private static final int MAX_SESSIONS =
            Math.max(1, Integer.getInteger("retroconsole.maxPcsx2Sessions", 8));

    private static volatile String lastRefuseReason;

    private Pcsx2MemcardSync() {}

    /** One live PS2 session: private systemDir for GET_SYSTEM_DIRECTORY. */
    public record Session(int id, Path systemDir, Path liveMemcards, PlayerPaths paths) {}

    public static String lastRefuseReason() {
        return lastRefuseReason;
    }

    public static int maxSessions() {
        return MAX_SESSIONS;
    }

    public static int liveSessions() {
        return LIVE.size();
    }

    /**
     * Prepare a private system tree and copy player stash → live memcards.
     * @return session, or {@code null} if refused (limit / missing BIOS)
     */
    public static Session install(PlayerPaths paths) {
        PlayerPaths p = paths != null ? paths : PlayerPaths.shared();
        synchronized (LOCK) {
            if (LIVE.size() >= MAX_SESSIONS) {
                lastRefuseReason = "PS2 session limit (" + MAX_SESSIONS
                        + ") reached — stop another PS2 first";
                LOGGER.error("{} — refused new session.", lastRefuseReason);
                return null;
            }

            Path sharedBios = RetroConsolePaths.pcsx2BiosDir();
            if (!hasBiosFiles(sharedBios)) {
                lastRefuseReason = "No PS2 BIOS in " + sharedBios
                        + " — put SCPH-*.BIN (or similar) there";
                LOGGER.error(lastRefuseReason);
                return null;
            }

            int id = NEXT_ID.incrementAndGet();
            Path systemRoot = RetroConsolePaths.systemDir()
                    .resolve(".sessions")
                    .resolve("ps2-" + id)
                    .toAbsolutePath()
                    .normalize();
            Path live = systemRoot.resolve("pcsx2").resolve("memcards");
            Path biosLink = systemRoot.resolve("pcsx2").resolve("bios");
            mkdirs(live);
            ensureBiosJunction(biosLink, sharedBios);

            if (!hasBiosFiles(biosLink)) {
                lastRefuseReason = "PS2 BIOS junction failed — nothing visible at " + biosLink;
                LOGGER.error("{} (shared={})", lastRefuseReason, sharedBios);
                return null;
            }

            Path stash = p.pcsx2MemcardsDir();
            mkdirs(stash);
            int n = copyMemcards(stash, live);
            Session session = new Session(id, systemRoot, live, p);
            LIVE.put(id, session);
            lastRefuseReason = null;
            LOGGER.info("PS2 session {}/{}: system={} memcards={} ({} file(s) from stash {})",
                    LIVE.size(), MAX_SESSIONS, systemRoot, live, n, stash);
            return session;
        }
    }

    /** Copy live memcards → player stash and drop the session bookkeeping. */
    public static void export(Session session) {
        if (session == null) return;
        synchronized (LOCK) {
            LIVE.remove(session.id());
            PlayerPaths paths = session.paths();
            if (paths != null && paths.isPerPlayer()) {
                Path stash = paths.pcsx2MemcardsDir();
                mkdirs(stash);
                int n = copyMemcards(session.liveMemcards(), stash);
                LOGGER.info("PS2 session {}: export {} file(s) -> {} (live now {})",
                        session.id(), n, stash, LIVE.size());
            } else {
                LOGGER.info("PS2 session {}: closed (live now {})", session.id(), LIVE.size());
            }
        }
    }

    /** @deprecated use {@link #install(PlayerPaths)} + {@link #export(Session)} */
    @Deprecated
    public static void installLegacy(PlayerPaths paths) {
        install(paths);
    }

    /** @deprecated use {@link #export(Session)} */
    @Deprecated
    public static void export(PlayerPaths paths) {
        if (paths == null) return;
        UUID id = paths.playerId();
        Session match = null;
        synchronized (LOCK) {
            for (Session s : LIVE.values()) {
                if (s.paths() != null && id.equals(s.paths().playerId())) {
                    match = s;
                }
            }
        }
        if (match != null) {
            export(match);
        }
    }

    private static boolean hasBiosFiles(Path biosDir) {
        if (!Files.isDirectory(biosDir)) return false;
        try (Stream<Path> files = Files.list(biosDir)) {
            return files.anyMatch(Pcsx2MemcardSync::looksLikeBios);
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean looksLikeBios(Path p) {
        if (!Files.isRegularFile(p)) return false;
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".nvm") || name.endsWith(".txt") || name.startsWith(".")) return false;
        return name.endsWith(".bin") || name.endsWith(".rom") || name.endsWith(".mec")
                || name.contains("scph") || name.contains("bios");
    }

    private static void ensureBiosJunction(Path link, Path target) {
        try {
            if (Files.exists(link)) return;
            Files.createDirectories(link.getParent());
            if (createDirectoryJunction(link, target)) {
                LOGGER.info("PS2 BIOS junction {} -> {}", link, target);
                return;
            }
            mkdirs(link);
            if (!Files.isDirectory(target)) return;
            try (Stream<Path> files = Files.list(target)) {
                for (Path src : files.filter(Files::isRegularFile).toList()) {
                    Path dst = link.resolve(src.getFileName());
                    try {
                        Files.createLink(dst, src);
                    } catch (Exception ignored) {
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            LOGGER.warn("PS2 BIOS junction failed; copied/linked files into {}", link);
        } catch (IOException ex) {
            LOGGER.warn("Failed to prepare BIOS at {}: {}", link, ex.getMessage());
        }
    }

    private static boolean createDirectoryJunction(Path link, Path target) {
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
