package com.retroconsole.platform;

import com.retroconsole.config.RetroConfigJson;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge glue between the legacy {@code ModConfig} toml files and the
 * unified {@code config/retroconsole.json}.
 *
 * <p>The legacy values live in three specs that load at different moments:
 * {@code common} (paths) before common setup, {@code client} (gamepad) during
 * client startup, and {@code server} (streaming / limits / video) only when a
 * world starts. So the import is incremental: {@link #install()} writes the
 * file with what is readable then, and {@link #onConfigLoaded(ModConfig)} fills
 * in the rest as those configs appear.
 *
 * <p>Until the server section has been imported {@code legacyTomlImported}
 * stays {@code false} in the file and {@link #needsMigration()} keeps the specs
 * registered on the next launch, so a player who never entered a world does not
 * silently drop their tuned server values. Once the flag is set the specs are
 * no longer registered and the JSON is the only source — no TOML parser is
 * needed anywhere.
 */
public final class NeoForgeConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Config");

    /** The tree being written while the import is incomplete. */
    private static RetroConfigJson draft;
    /** True while the legacy server config has not been copied yet. */
    private static boolean awaitingServer;
    /** True on the launch that created the file, i.e. sections may still load. */
    private static boolean wroteFile;

    private NeoForgeConfig() {}

    /** True while some legacy toml section still has to be copied into the JSON. */
    public static boolean needsMigration() {
        return !RetroConfigJson.legacyImportDone();
    }

    /**
     * Called from common setup. Creates {@code retroconsole.json} from the
     * toml values that are loaded by now, or reads it when it already exists.
     */
    public static void install() {
        wroteFile = !RetroConfigJson.exists();
        if (wroteFile) {
            draft = new RetroConfigJson();
            draft.copyPaths(NeoForgeSettings.INSTANCE);
            draft.copyInput(NeoForgeSettings.INSTANCE);
            draft.save();
            RetroConfigJson.install(draft);
            LOGGER.info("Imported legacy NeoForge config into {}", RetroConfigJson.file());
        } else {
            draft = RetroConfigJson.load();
        }

        awaitingServer = !draft.legacyTomlImported;
        if (awaitingServer) {
            LOGGER.info("Legacy server config is imported once a world starts");
        }
        logSummary();
    }

    /** Feeds a freshly loaded legacy spec into the pending import. */
    public static void onConfigLoaded(ModConfig config) {
        if (draft == null) return;
        ModConfig.Type type = config.getType();

        if (type == ModConfig.Type.SERVER && awaitingServer) {
            draft.copyStreaming(NeoForgeSettings.INSTANCE);
            draft.copyLimits(NeoForgeSettings.INSTANCE);
            draft.copyVideo(NeoForgeSettings.INSTANCE);
            draft.legacyTomlImported = true;
            draft.save();
            awaitingServer = false;
            wroteFile = false;
            LOGGER.info("Imported legacy NeoForge server config into {}", RetroConfigJson.file());
            return;
        }

        // The client section may load before or after common setup, so on the
        // creating launch it is copied whenever it shows up.
        if (wroteFile && type == ModConfig.Type.CLIENT) {
            draft.copyInput(NeoForgeSettings.INSTANCE);
            draft.save();
        }
    }

    /** Re-read the JSON from disk (server start / explicit reload). */
    public static void reload() {
        draft = RetroConfigJson.load();
        // A reload can happen before the server config was imported (the JSON
        // is created during setup), so the pending state is re-derived.
        awaitingServer = !draft.legacyTomlImported;
    }

    private static void logSummary() {
        RetroConfigJson cfg = RetroConfigJson.get();
        LOGGER.info("Config {}: coresDir={}, preset={}, videoDistance={}",
                RetroConfigJson.file(), cfg.coresPath(), cfg.videoPreset(), cfg.videoDistance());
    }
}
