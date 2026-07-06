package com.retroconsole.platform;

import java.nio.file.Path;

/** Paths for libretro-compatible save files under a player's save directory. */
public final class SaveFiles {

    private SaveFiles() {}

    public static Path batterySavePath(PlayerPaths paths, Path romPath) {
        return batterySavePath(paths.saveDir(), romPath);
    }

    public static Path batterySavePath(Path saveRoot, Path romPath) {
        return saveRoot.resolve(romPath.getFileName().toString() + ".srm");
    }

    public static Path saveStatePath(PlayerPaths paths, Path romPath, int slot) {
        return saveStatePath(paths.saveDir(), romPath, slot);
    }

    public static Path saveStatePath(Path saveRoot, Path romPath, int slot) {
        return saveRoot.resolve(romPath.getFileName().toString() + ".state" + slot);
    }
}
