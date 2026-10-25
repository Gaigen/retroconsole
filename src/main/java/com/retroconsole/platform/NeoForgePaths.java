package com.retroconsole.platform;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge {@link RetroPaths}: the game directory is the one FML resolved for
 * this launch (the run directory under {@code run/}, the instance root when
 * installed). Everything else is derived from {@code RetroSettings}.
 */
public final class NeoForgePaths implements RetroPaths {

    public static final NeoForgePaths INSTANCE = new NeoForgePaths();

    private NeoForgePaths() {}

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }
}
