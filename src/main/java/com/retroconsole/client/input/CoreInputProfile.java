package com.retroconsole.client.input;

import com.retroconsole.library.GameSystem;

import java.nio.file.Path;
import java.util.Locale;

/** Determines extended input behaviour (touch, extra buttons) per loaded core/ROM. */
public enum CoreInputProfile {
    STANDARD,
    TOUCH_DUAL_SCREEN;

    public static CoreInputProfile forSession(String coreName, GameSystem system) {
        if (system != null) {
            if ("NDS".equalsIgnoreCase(system.id)) return TOUCH_DUAL_SCREEN;
            if ("N3DS".equalsIgnoreCase(system.id)) return TOUCH_DUAL_SCREEN;
        }
        String lower = coreName == null ? "" : coreName.toLowerCase(Locale.ROOT);
        if (lower.contains("melonds") || lower.contains("desmume")) return TOUCH_DUAL_SCREEN;
        if (lower.contains("citra")) return TOUCH_DUAL_SCREEN;
        return STANDARD;
    }

    /** Client-side detection by system id when the core name is not known yet. */
    public static CoreInputProfile forSystemId(String systemId) {
        if (systemId == null) return STANDARD;
        return switch (systemId.toUpperCase(Locale.ROOT)) {
            case "NDS", "N3DS" -> TOUCH_DUAL_SCREEN;
            default -> STANDARD;
        };
    }

    public static CoreInputProfile forCorePath(Path corePath) {
        if (corePath == null) return STANDARD;
        return forSession(corePath.getFileName().toString(), null);
    }
}
