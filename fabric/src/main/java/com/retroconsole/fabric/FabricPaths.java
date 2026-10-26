package com.retroconsole.fabric;

import com.retroconsole.platform.RetroPaths;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Fabric {@link RetroPaths}: the instance root Loom resolved for this launch.
 * Everything else is derived from {@code RetroSettings}, exactly as on NeoForge.
 */
public final class FabricPaths implements RetroPaths {

    public static final FabricPaths INSTANCE = new FabricPaths();

    private FabricPaths() {}

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
