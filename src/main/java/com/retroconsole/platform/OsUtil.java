package com.retroconsole.platform;

import java.util.Locale;

/**
 * Operating-system detection helper used to pick the correct libretro / headless-GL
 * implementation at runtime.
 *
 * <p>This is intentionally minimal: only what {@link com.retroconsole.bridge.LibretroCore}'s
 * factory and {@code CoreManager} need. Add more knobs here when new platform-specific
 * behaviour is introduced.
 */
public final class OsUtil {

    public enum OsFamily {
        LINUX,
        WINDOWS,
        MACOS,
        UNKNOWN
    }

    private static final OsFamily CURRENT = detect();

    private OsUtil() {}

    public static OsFamily current() {
        return CURRENT;
    }

    public static boolean isLinux() {
        return CURRENT == OsFamily.LINUX;
    }

    public static boolean isWindows() {
        return CURRENT == OsFamily.WINDOWS;
    }

    public static boolean isMacos() {
        return CURRENT == OsFamily.MACOS;
    }

    /** File extension used by shared libraries on the current OS. */
    public static String nativeLibExt() {
        return switch (CURRENT) {
            case LINUX -> "so";
            case WINDOWS -> "dll";
            case MACOS -> "dylib";
            case UNKNOWN -> "so";
        };
    }

    /**
     * Bundled headless GL library name inside {@code /natives/} (without dot prefix).
     * Extracted to cores dir as {@code .libheadless_gl.<ext>}.
     */
    public static String bundledHeadlessGlName() {
        return switch (CURRENT) {
            case LINUX -> "libheadless_gl.so";
            case WINDOWS -> "libheadless_gl.dll";
            default -> null;
        };
    }

    private static OsFamily detect() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) return OsFamily.LINUX;
        if (os.contains("win")) return OsFamily.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OsFamily.MACOS;
        return OsFamily.UNKNOWN;
    }
}
