package com.retroconsole.fabric;

import com.retroconsole.config.ConfigAccess;
import com.retroconsole.config.RetroConfigJson;
import com.retroconsole.library.ArtFiles;
import com.retroconsole.platform.RetroConsolePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config bootstrapping for Fabric.
 *
 * <p>There are no legacy toml files on this loader, so the unified
 * {@code config/retroconsole.json} is written with the defaults on first launch
 * and is the only source of truth from then on. The import flag is set right
 * away: a player who later switches to the NeoForge build must not have their
 * tuned JSON overwritten by that loader's toml migration.
 */
public final class FabricConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Config");

    private FabricConfig() {}

    public static void install() {
        if (RetroConfigJson.exists()) {
            RetroConfigJson.load();
        } else {
            RetroConfigJson cfg = new RetroConfigJson();
            cfg.legacyTomlImported = true;
            cfg.save();
            RetroConfigJson.install(cfg);
            LOGGER.info("Created default config {}", RetroConfigJson.file());
        }

        // Re-read on server start / explicit reload, same as NeoForge.
        ConfigAccess.setReloader(RetroConfigJson::load);

        // Console cover art is generated once, before anyone opens the library.
        ArtFiles.ensureDefaults(RetroConsolePaths.artDir());
        RetroConsolePaths.ensureAllExist();

        RetroConfigJson cfg = RetroConfigJson.get();
        LOGGER.info("Config {}: coresDir={}, preset={}, videoDistance={}",
                RetroConfigJson.file(), cfg.coresPath(), cfg.videoPreset(), cfg.videoDistance());
    }
}
