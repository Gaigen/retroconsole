package com.retroconsole.platform;

import com.retroconsole.bridge.LibretroCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SaveStateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("SaveState");
    public static final int MAX_SLOT = 0;

    private SaveStateManager() {}

    public static boolean supportsSaveStates(LibretroCore core) {
        if (core.isPcsx2Core()) return false;
        return core.getSerializeSize() > 0;
    }

    public static boolean save(LibretroCore core, Path romPath, PlayerPaths paths, int slot) {
        if (!supportsSaveStates(core)) {
            LOGGER.info("Save state not supported for this core");
            return false;
        }
        byte[] data = core.serialize();
        if (data == null || data.length == 0) return false;
        Path file = SaveFiles.saveStatePath(paths, romPath, slot);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data);
            LOGGER.info("Save state slot {} -> {} ({} bytes)", slot, file.getFileName(), data.length);
            return true;
        } catch (Exception ex) {
            LOGGER.warn("Failed to write save state {}: {}", file, ex.getMessage());
            return false;
        }
    }

    public static boolean saveAuto(LibretroCore core, String romId, PlayerPaths paths) {
        if (!supportsSaveStates(core)) {
            LOGGER.info("Auto save state not supported for this core");
            return false;
        }
        byte[] data = core.serialize();
        if (data == null || data.length == 0) return false;
        Path file = SaveFiles.autoStatePath(paths, romId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data);
            LOGGER.info("Auto save state -> {} ({} bytes)", file.getFileName(), data.length);
            return true;
        } catch (Exception ex) {
            LOGGER.warn("Failed to write auto save state {}: {}", file, ex.getMessage());
            return false;
        }
    }

    public static boolean load(LibretroCore core, Path romPath, PlayerPaths paths, int slot) {
        if (!supportsSaveStates(core)) {
            LOGGER.info("Save state not supported for this core");
            return false;
        }
        Path file = SaveFiles.saveStatePath(paths, romPath, slot);
        if (!Files.isRegularFile(file)) {
            LOGGER.info("No save state in slot {} ({})", slot, file.getFileName());
            return false;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            if (core.unserialize(data)) {
                LOGGER.info("Loaded save state slot {} from {} ({} bytes)", slot, file.getFileName(), data.length);
                return true;
            }
            LOGGER.warn("Core rejected save state {}", file.getFileName());
            return false;
        } catch (Exception ex) {
            LOGGER.warn("Failed to load save state {}: {}", file, ex.getMessage());
            return false;
        }
    }

    public static boolean loadAuto(LibretroCore core, String romId, PlayerPaths paths) {
        if (!supportsSaveStates(core)) {
            LOGGER.info("Auto save state not supported for this core");
            return false;
        }
        Path file = SaveFiles.autoStatePath(paths, romId);
        if (!Files.isRegularFile(file)) {
            LOGGER.info("No auto save state ({})", file.getFileName());
            return false;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            if (core.unserialize(data)) {
                LOGGER.info("Loaded auto save state from {} ({} bytes)", file.getFileName(), data.length);
                return true;
            }
            LOGGER.warn("Core rejected auto save state {}", file.getFileName());
            return false;
        } catch (Exception ex) {
            LOGGER.warn("Failed to load auto save state {}: {}", file, ex.getMessage());
            return false;
        }
    }

    /** Autosave first, otherwise manual slot 0 (F5). */
    public static boolean loadAutoOrSlot(LibretroCore core, String romId, Path romPath, PlayerPaths paths) {
        if (loadAuto(core, romId, paths)) return true;
        return load(core, romPath, paths, 0);
    }
}
