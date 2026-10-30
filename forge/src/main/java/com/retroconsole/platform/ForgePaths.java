package com.retroconsole.platform;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge {@link RetroPaths}: the game directory is the one FML resolved for
 * this launch (the run directory under {@code run/}, the instance root when
 * installed). Everything else is derived from {@code RetroSettings}.
 */
public final class ForgePaths implements RetroPaths {

    public static final ForgePaths INSTANCE = new ForgePaths();

    private ForgePaths() {}

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }
}
