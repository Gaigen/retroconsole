package com.retroconsole.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/** Ensures directories exist before a core session starts. */
public final class SessionSaveSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger("SessionSaveSetup");

    private SessionSaveSetup() {}

    public static void prepare(Path corePath, Path romPath, PlayerPaths paths) {
        try {
            Files.createDirectories(paths.saveDir());
        } catch (Exception ex) {
            LOGGER.warn("Failed to create save dir {}: {}", paths.saveDir(), ex.getMessage());
        }

        if (CoreSavePolicy.isFlycastCore(corePath)) {
            Path dc = RetroConsolePaths.dreamcastDir();
            LOGGER.debug("Dreamcast system dir ready: {}", dc);
        }

        if (CoreSavePolicy.isPcsxRearmedCore(corePath)) {
            LOGGER.debug("PS1 session save dir: {} (memcard1=libretro -> {}.srm)",
                    paths.saveDir(), romPath.getFileName());
        }
    }
}
