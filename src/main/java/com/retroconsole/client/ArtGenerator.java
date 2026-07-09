package com.retroconsole.client;

import com.retroconsole.library.ArtFiles;
import com.retroconsole.library.GameSystem;
import com.retroconsole.platform.RetroConsolePaths;

import java.nio.file.Path;

/** Client wrapper: writes PNGs via {@link ArtFiles} and invalidates the texture cache. */
public final class ArtGenerator {

    private ArtGenerator() {}

    public static Path artDir() {
        return RetroConsolePaths.artDir();
    }

    public static void ensure(GameSystem system) {
        if (system == null) return;
        Path dir = artDir();
        ArtFiles.ensure(dir, system);
        TextureCache.invalidate(dir.resolve(system.folder + ".png"));
    }
}
