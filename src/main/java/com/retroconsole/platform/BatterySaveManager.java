package com.retroconsole.platform;

import com.retroconsole.bridge.LibretroCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class BatterySaveManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("BatterySave");

    private BatterySaveManager() {}

    public static void loadIntoCore(LibretroCore core, Path romPath, PlayerPaths paths) {
        loadIntoCore(core, romPath, paths.saveDir());
    }

    public static void loadIntoCore(LibretroCore core, Path romPath, Path saveRoot) {
        if (!CoreSavePolicy.usesFrontendBatteryRam(core.getCorePath())) return;
        Path file = SaveFiles.batterySavePath(saveRoot, romPath);
        if (!Files.isRegularFile(file)) return;
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length == 0) return;
            if (core.setSaveRam(data)) {
                LOGGER.info("Loaded battery save: {} ({} bytes)", file.getFileName(), data.length);
            } else {
                LOGGER.warn("Battery save not applied (SRAM not ready?): {}", file.getFileName());
            }
        } catch (Exception ex) {
            LOGGER.warn("Failed to load battery save {}: {}", file, ex.getMessage());
        }
    }

    public static boolean saveFromCore(LibretroCore core, Path romPath, PlayerPaths paths) {
        return saveFromCore(core, romPath, paths.saveDir());
    }

    public static boolean saveFromCore(LibretroCore core, Path romPath, Path saveRoot) {
        if (!CoreSavePolicy.usesFrontendBatteryRam(core.getCorePath())) return false;
        byte[] sram = core.getSaveRam();
        if (sram == null || sram.length == 0) return false;
        Path file = SaveFiles.batterySavePath(saveRoot, romPath);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, sram);
            LOGGER.debug("Saved battery: {} ({} bytes)", file.getFileName(), sram.length);
            return true;
        } catch (Exception ex) {
            LOGGER.warn("Failed to write battery save {}: {}", file, ex.getMessage());
            return false;
        }
    }
}
