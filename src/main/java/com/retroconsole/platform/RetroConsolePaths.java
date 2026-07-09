package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import com.retroconsole.config.ModConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for every filesystem location the mod touches:
 * cores, ROMs, system files, saves, and the per-platform subfolders
 * (for example {@code system/pcsx2/bios}).
 *
 * <p>All path resolution goes through this class. Other parts of the mod
 * (CoreManager, ServerConsoles, client GUI) must not call
 * {@code FMLPaths.GAMEDIR.get().resolve(...)} directly — go through
 * {@link #coresDir()}, {@link #romsDir()}, etc., so the filesystem layout
 * lives in exactly one place.
 *
 * <p><b>Why no eager creation in {@code RetroConsole}?</b> NeoForge does
 * not load {@code ModConfigSpec.ConfigValue#get()} until the config spec
 * has been registered and {@link #FMLPaths} is initialised. Reading
 * {@code ModConfig.CORES_DIR.get()} at mod-construction time throws
 * {@code IllegalStateException}. We resolve paths lazily on first access,
 * after which the result is cached — the directory will exist by the time
 * any caller actually opens a file.
 */
@EventBusSubscriber(modid = RetroConsole.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class RetroConsolePaths {
    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsolePaths");

    private RetroConsolePaths() {}

    /**
     * Fires during the mod event bus lifecycle, after the config spec for
     * {@code RetroConsole} has been registered AND its TOML file has been
     * loaded by ConfigTracker. At this point {@link ModConfig#CORES_DIR}
     * and friends are safe to call — earlier, {@code .get()} throws
     * {@code IllegalStateException}.
     *
     * <p>We enqueue the directory creation onto the main thread so that
     * filesystem work doesn't block the setup pipeline.
     */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(RetroConsolePaths::ensureAllExist);
    }

    // ----- Per-folder accessors (lazy, cached) ----------------------------

    private static final Map<String, Path> CACHE = new ConcurrentHashMap<>();

    /** Default roms/ subfolders for built-in systems; created at startup. */
    private static final List<String> DEFAULT_ROM_FOLDERS = List.of(
            "nes", "snes", "gb", "gba", "genesis", "sms",
            "ps1", "ps2", "psp", "dreamcast", "segacd", "saturn");

    public static Path coresDir() {
        return ensureDir("cores", ModConfig.CORES_DIR.get());
    }

    public static Path romsDir() {
        return ensureDir("roms", ModConfig.ROMS_DIR.get());
    }

    public static Path systemDir() {
        return ensureDir("system", ModConfig.SYSTEM_DIR.get());
    }

    public static Path saveDir() {
        return ensureDir("saves", ModConfig.SAVE_DIR.get());
    }

    /**
     * Resolve a catalog {@code romId} (path relative to {@link #romsDir()}) to a file.
     * Rejects path traversal ({@code ..}) and paths that escape the roms root.
     */
    public static Optional<Path> resolveRomFile(String romId) {
        if (romId == null || romId.isBlank()) {
            return Optional.empty();
        }
        Path romsRoot = romsDir().toAbsolutePath().normalize();
        Path resolved = romsRoot.resolve(romId.replace('\\', '/')).normalize();
        if (!resolved.startsWith(romsRoot)) {
            LOGGER.warn("Rejected romId outside roms dir: {}", romId);
            return Optional.empty();
        }
        if (!Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    /** Console card art in the menu: config/retroconsole/art/{folder}.png */
    public static Path artDir() {
        return ensureDir("art", "config/retroconsole/art");
    }

    /** Per-player saves and server stats: saves/players/{uuid}/ */
    public static Path playersSaveDir() {
        Path p = saveDir().resolve("players").toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
        } catch (Exception ex) {
            LOGGER.error("Failed to create players save directory at {}: {}", p, ex.getMessage());
        }
        return p;
    }

    /** PCSX2 needs its BIOS under {@code system/pcsx2/bios} — see
     *  {@link Pcsx2BiosResolver}.
     *  <p>Note: this key is intentionally NOT prefixed with the system path —
     *  it resolves under {@link #systemDir()} so we end up with
     *  {@code <gameDir>/system/pcsx2/bios}. */
    public static Path pcsx2BiosDir() {
        // Resolution goes through systemDir() first so the cache key includes
        // the full path of the parent. Computing the join here would also work,
        // but doing it this way keeps the cache keys consistent with
        // coresDir()/romsDir()/etc., which cache the *full* path.
        Path parent = systemDir();
        Path p = parent.resolve("pcsx2").resolve("bios").toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
        } catch (Exception ex) {
            LOGGER.error("Failed to create pcsx2/bios directory at {}: {}", p, ex.getMessage());
        }
        return p;
    }

    /**
     * LRPS2 stores PS2 memory cards here (NOT in {@link #saveDir()}).
     * @see <a href="https://docs.libretro.com/library/lrps2/">LRPS2 Directories</a>
     */
    public static Path pcsx2MemcardsDir() {
        Path p = systemDir().resolve("pcsx2").resolve("memcards")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
        } catch (Exception ex) {
            LOGGER.error("Failed to create pcsx2/memcards directory at {}: {}", p, ex.getMessage());
        }
        return p;
    }

    /** Flycast BIOS / shared VMU files (when per-game VMUs are disabled). */
    public static Path dreamcastDir() {
        Path p = systemDir().resolve("dc").toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
        } catch (Exception ex) {
            LOGGER.error("Failed to create Dreamcast system directory at {}: {}", p, ex.getMessage());
        }
        return p;
    }

    /** One-shot: make sure every RetroConsole-controlled directory exists. */
    public static void ensureAllExist() {
        coresDir();
        romsDir();
        artDir();
        systemDir();
        saveDir();
        playersSaveDir();
        ensureDefaultRomFolders();
        pcsx2BiosDir();
        pcsx2MemcardsDir();
        dreamcastDir();
        logPathsSummary();
    }

    private static void ensureDefaultRomFolders() {
        Path roms = romsDir();
        for (String folder : DEFAULT_ROM_FOLDERS) {
            Path p = roms.resolve(folder).toAbsolutePath().normalize();
            if (!p.startsWith(roms.toAbsolutePath().normalize())) continue;
            try {
                Files.createDirectories(p);
            } catch (Exception ex) {
                LOGGER.warn("Failed to create roms/{}: {}", folder, ex.getMessage());
            }
        }
    }

    /** Print a one-line summary of the resolved paths. Safe to call once ModConfig
     *  is loaded; throws if called too early, but here we are only called after
     *  registration so this is fine. */
    public static void logPathsSummary() {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            LOGGER.info("RetroConsole paths:");
            LOGGER.info("  cores      -> {}", coresDir().toAbsolutePath().normalize());
            LOGGER.info("  roms       -> {}", romsDir().toAbsolutePath().normalize());
            LOGGER.info("  art        -> {}", artDir().toAbsolutePath().normalize());
            LOGGER.info("  system     -> {}", systemDir().toAbsolutePath().normalize());
            LOGGER.info("  saves      -> {}", saveDir().toAbsolutePath().normalize());
            LOGGER.info("  players    -> {} (per-player saves + playstats)", playersSaveDir().toAbsolutePath().normalize());
            LOGGER.info("  pcsx2/bios -> {}", pcsx2BiosDir().toAbsolutePath().normalize());
            LOGGER.info("  pcsx2/mcd  -> {} (PS2 memory cards)", pcsx2MemcardsDir().toAbsolutePath().normalize());
            LOGGER.info("  dc/        -> {} (Dreamcast BIOS / shared VMU)", dreamcastDir().toAbsolutePath().normalize());
        } catch (Exception ex) {
            LOGGER.warn("Could not log RetroConsole paths yet: {}", ex.getMessage());
        }
    }

    // ----- helpers --------------------------------------------------------

    /** Resolve {@code relativeFromConfig} against the game directory and
     *  ensure the resulting directory exists. Cached by {@code relativeFromConfig}
     *  so we hit the filesystem at most once per directory per session. */
    private static Path ensureDir(String label, String relativeFromConfig) {
        return CACHE.computeIfAbsent(relativeFromConfig, key -> {
            Path p = FMLPaths.GAMEDIR.get().resolve(key).toAbsolutePath().normalize();
            try {
                Files.createDirectories(p);
            } catch (Exception ex) {
                LOGGER.error("Failed to create {} directory at {}: {}",
                        label, p, ex.getMessage());
            }
            return p;
        });
    }
}
