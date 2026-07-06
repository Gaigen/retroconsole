package com.retroconsole.emu;

import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.platform.Pcsx2MemcardSync;
import com.retroconsole.platform.PlayerPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages available libretro cores.
 * Discovers .dll/.so files in the cores directory,
 * provides them for selection in the GUI.
 */
public class CoreManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CoreManager");

    private final Path coresDir;
    private final Path systemDir;
    private final Path saveDir;
    private final List<CoreInfo> discovered = new ArrayList<>();

    public record CoreInfo(String name, Path path, String platform) {}

    public CoreManager(Path coresDir, Path systemDir, Path saveDir) {
        this.coresDir = coresDir.toAbsolutePath().normalize();
        this.systemDir = systemDir.toAbsolutePath().normalize();
        this.saveDir = saveDir.toAbsolutePath().normalize();

        // Directory creation is owned by RetroConsolePaths.ensureAllExist(...),
        // which runs at mod construction time. We do not create directories here.
        // Extract bundled native libraries from mod jar
        extractBundledNatives(coresDir);
    }

    /**
     * Extract bundled .libheadless_gl.so from mod resources to cores dir.
     * Only extracts if the file doesn't exist or is outdated.
     */
    private static void extractBundledNatives(Path coresDir) {
        String bundledName = com.retroconsole.platform.OsUtil.bundledHeadlessGlName();
        if (bundledName == null) {
            return;
        }
        String[] bundled = { bundledName };
        for (String name : bundled) {
            String resourceName = "/natives/" + name;
            // Our file is stored as .libheadless_gl.so (dot-prefixed, hidden)
            String targetName = "." + name;
            Path target = coresDir.resolve(targetName);

            try (var in = CoreManager.class.getResourceAsStream(resourceName)) {
                if (in == null) {
                    LOGGER.debug("No bundled native: {}", resourceName);
                    continue;
                }

                // Check if existing file matches by size
                byte[] bytes = in.readAllBytes();
                if (Files.exists(target)) {
                    long existingSize = Files.size(target);
                    if (existingSize == bytes.length) {
                        LOGGER.debug("Bundled native {} is up to date ({} bytes)", targetName, bytes.length);
                        continue;
                    }
                }

                Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                target.toFile().setExecutable(true);
                LOGGER.info("Extracted bundled native: {} ({} bytes)", targetName, bytes.length);
            } catch (IOException e) {
                LOGGER.warn("Failed to extract bundled native {}: {}", name, e.getMessage());
            }
        }
    }

    /**
     * Scan the cores directory for native libraries.
     */
    public void discoverCores() {
        discovered.clear();

        if (!Files.exists(coresDir)) {
            LOGGER.warn("Cores directory does not exist: {}", coresDir);
            return;
        }

        try (var stream = Files.list(coresDir)) {
            List<Path> files = stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        if (name.startsWith(".")) return false;
                        return name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib");
                    })
                    .sorted()
                    .collect(Collectors.toList());

            for (Path f : files) {
                String name = f.getFileName().toString();
                String platform;
                if (name.endsWith(".so")) platform = "linux";
                else if (name.endsWith(".dll")) platform = "windows";
                else if (name.endsWith(".dylib")) platform = "macos";
                else platform = "unknown";

                // Strip extension for display name
                String displayName = name.contains(".") ?
                        name.substring(0, name.lastIndexOf('.')) : name;

                discovered.add(new CoreInfo(displayName, f, platform));
                LOGGER.info("Discovered core: {} ({})", displayName, platform);
            }

            LOGGER.info("Found {} libretro core(s)", discovered.size());
        } catch (IOException e) {
            LOGGER.error("Failed to scan cores directory", e);
        }
    }

    /**
     * Get list of discovered cores.
     */
    public List<CoreInfo> getCores() {
        return Collections.unmodifiableList(discovered);
    }

    /**
     * Find a core by name.
     */
    public CoreInfo findCore(String name) {
        return discovered.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Load a core and create a LibretroRuntime.
     *
     * @param corePath Path to the core .dll/.so
     * @param romPath  Path to the ROM file
     * @return LibretroRuntime wrapping the loaded core, or null on failure
     */
    public LibretroRuntime loadCoreAndGame(Path corePath, Path romPath, PlayerPaths playerPaths) {
        boolean pcsx2 = corePath.getFileName().toString().toLowerCase().contains("pcsx2");
        if (pcsx2) {
            Pcsx2MemcardSync.install(playerPaths);
        }
        try {
            LibretroCore core = LibretroCore.load(
                    corePath,
                    playerPaths.systemDir().toString(),
                    playerPaths.saveDir().toString());

            if (!core.loadGame(romPath)) {
                core.close();
                if (pcsx2) {
                    Pcsx2MemcardSync.export(playerPaths);
                }
                return null;
            }

            LibretroRuntime runtime = new LibretroRuntime(core, romPath, playerPaths);
            return runtime;
        } catch (Exception e) {
            if (pcsx2) {
                Pcsx2MemcardSync.export(playerPaths);
            }
            LOGGER.error("Failed to load core {} with ROM {}", corePath, romPath, e);
            return null;
        }
    }

    /** @deprecated use {@link #loadCoreAndGame(Path, Path, PlayerPaths)} */
    @Deprecated
    public LibretroRuntime loadCoreAndGame(Path corePath, Path romPath) {
        return loadCoreAndGame(corePath, romPath, PlayerPaths.shared());
    }

    public Path getCoresDir() { return coresDir; }
    public Path getSystemDir() { return systemDir; }
    public Path getSaveDir() { return saveDir; }
}
