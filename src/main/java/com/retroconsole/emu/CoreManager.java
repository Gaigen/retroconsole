package com.retroconsole.emu;

import com.retroconsole.bridge.LibretroCore;
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
        this.coresDir = coresDir;
        this.systemDir = systemDir;
        this.saveDir = saveDir;

        try {
            Files.createDirectories(coresDir);
            Files.createDirectories(systemDir);
            Files.createDirectories(saveDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create directories", e);
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
    public LibretroRuntime loadCoreAndGame(Path corePath, Path romPath) {
        try {
            LibretroCore core = LibretroCore.load(corePath);
            core.setDirectories(systemDir.toString(), saveDir.toString());

            if (!core.loadGame(romPath)) {
                core.close();
                return null;
            }

            LibretroRuntime runtime = new LibretroRuntime(core, romPath);
            return runtime;
        } catch (Exception e) {
            LOGGER.error("Failed to load core {} with ROM {}", corePath, romPath, e);
            return null;
        }
    }

    public Path getCoresDir() { return coresDir; }
    public Path getSystemDir() { return systemDir; }
    public Path getSaveDir() { return saveDir; }
}
