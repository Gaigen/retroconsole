package com.retroconsole.platform;

import java.nio.file.Path;

/**
 * Libretro cores use different save mechanisms. Frontend-managed battery RAM
 * ({@code RETRO_MEMORY_SAVE_RAM}) must not be flushed for cores that write their
 * own files under {@code GET_SAVE_DIRECTORY} (PS1 memory cards, Dreamcast VMU).
 */
public final class CoreSavePolicy {

    private CoreSavePolicy() {}

    public static boolean usesFrontendBatteryRam(Path corePath) {
        if (corePath == null) return true;
        String name = corePath.getFileName().toString().toLowerCase();
        if (name.contains("beetle_psx") || name.contains("swanstation")) {
            return false;
        }
        // PCSX-ReARMed memcard1=libretro exposes slot 1 via RETRO_MEMORY_SAVE_RAM.
        if (name.contains("flycast")) return false;
        if (name.contains("ppsspp")) return false;
        if (name.contains("pcsx2")) return false;
        return true;
    }

    public static boolean isFlycastCore(Path corePath) {
        return corePath != null && corePath.getFileName().toString().toLowerCase().contains("flycast");
    }

    public static boolean isPcsxRearmedCore(Path corePath) {
        if (corePath == null) return false;
        String name = corePath.getFileName().toString().toLowerCase();
        return name.contains("pcsx_rearmed");
    }
}
