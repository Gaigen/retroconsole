package com.retroconsole.platform;

import com.retroconsole.config.RetroSettings;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Filesystem roots the mod works with, resolved without a loader API.
 *
 * <p>The only thing a loader has to supply is {@link #gameDir()}: NeoForge uses
 * {@code FMLPaths.GAMEDIR}, Fabric {@code FabricLoader#getGameDir}. The
 * configurable sub-directories come from {@link RetroSettings}, so switching
 * the config backend does not touch this class.
 *
 * <p>{@link RetroConsolePaths} caches, creates and derives everything else
 * (players/, pcsx2/bios/, dc/, citra/ …).
 */
public interface RetroPaths {

    /** Filesystem root the relative config paths are resolved against. */
    Path gameDir();

    default Path coresDir() {
        return resolve(RetroSettings.coresPath());
    }

    default Path romsDir() {
        return resolve(RetroSettings.romsPath());
    }

    default Path systemDir() {
        return resolve(RetroSettings.systemPath());
    }

    default Path saveDir() {
        return resolve(RetroSettings.savePath());
    }

    default Path artDir() {
        return resolve(RetroSettings.artPath());
    }

    /** Resolve a config-relative path against {@link #gameDir()}. */
    default Path resolve(String relative) {
        return gameDir().resolve(relative).toAbsolutePath().normalize();
    }

    /** Used by the loader entrypoint. */
    static void set(RetroPaths implementation) {
        if (implementation != null) Holder.current = implementation;
    }

    static RetroPaths get() {
        return Holder.current;
    }

    /** Fallback for headless runs, unit tests and pre-init calls. */
    RetroPaths DEFAULT = () -> Paths.get("").toAbsolutePath();

    /** Interface fields are final, so the mutable slot lives in a nested holder. */
    final class Holder {
        private Holder() {}

        static RetroPaths current = DEFAULT;
    }
}
