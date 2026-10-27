package com.retroconsole.config;

import java.util.Locale;

/**
 * Loader-agnostic view of every scalar setting the mod reads.
 *
 * <p>Values live behind a {@link Source}. The loader entrypoint installs the
 * real one — {@code NeoForgeSettings} on NeoForge, the JSON reader once the
 * unified config lands — so shared code ({@code bridge/}, {@code emu/},
 * {@code platform/}) never has to import a loader's config API.
 *
 * <p>Every getter falls back to the mod's default and never throws, so calls
 * made before the config has been loaded (mod construction, static init) are
 * always safe.
 */
public final class RetroSettings {

    private RetroSettings() {}

    private static volatile Source source = new Source() { };

    /** Called by the loader entrypoint once its config is available. */
    public static void setSource(Source value) {
        if (value != null) source = value;
    }

    public static Source source() {
        return source;
    }

    // ----- paths (relative to the game directory) --------------------------

    public static String coresPath() {
        return source.coresPath();
    }

    public static String romsPath() {
        return source.romsPath();
    }

    public static String systemPath() {
        return source.systemPath();
    }

    public static String savePath() {
        return source.savePath();
    }

    public static String artPath() {
        return source.artPath();
    }

    // ----- streaming -------------------------------------------------------

    public static int videoDistance() {
        return source.videoDistance();
    }

    public static int audioDistance() {
        return source.audioDistance();
    }

    public static int viewSubscribeDistance() {
        return source.viewSubscribeDistance();
    }

    public static int controlDistance() {
        return source.controlDistance();
    }

    public static int notifyDistance() {
        return source.notifyDistance();
    }

    public static int worldMaxWidth() {
        return source.worldMaxWidth();
    }

    // ----- limits ----------------------------------------------------------

    public static int maxCoreSlots() {
        return source.maxCoreSlots();
    }

    public static int maxPcsx2Sessions() {
        return source.maxPcsx2Sessions();
    }

    public static int maxScreenCluster() {
        return source.maxScreenCluster();
    }

    public static int batteryAutosaveSeconds() {
        return source.batteryAutosaveSeconds();
    }

    public static boolean enable3ds() {
        return source.enable3ds();
    }

    // ----- video -----------------------------------------------------------

    /** Normalised to {@code performance | balanced | quality}. */
    public static String videoPreset() {
        String p = source.videoPreset();
        if (p == null || p.isBlank()) return "balanced";
        return p.trim().toLowerCase(Locale.ROOT);
    }

    public static String flycastInternalResolution() {
        return source.flycastInternalResolution();
    }

    public static String flycastTexUpscale() {
        return source.flycastTexUpscale();
    }

    public static String flycastAnisotropic() {
        return source.flycastAnisotropic();
    }

    public static String ppssppInternalResolution() {
        return source.ppssppInternalResolution();
    }

    public static String ppssppTextureScaling() {
        return source.ppssppTextureScaling();
    }

    public static String pcsx2UpscaleMultiplier() {
        return source.pcsx2UpscaleMultiplier();
    }

    public static String pcsx2Anisotropic() {
        return source.pcsx2Anisotropic();
    }

    public static String citraResolution() {
        return source.citraResolution();
    }

    public static String doom3Resolution() {
        return source.doom3Resolution();
    }

    public static boolean doom3InvertY() {
        return source.doom3InvertY();
    }

    // ----- debug -----------------------------------------------------------

    /**
     * Opt-in chatter: periodic call-stats heartbeats and GL capture diagnostics.
     * Off by default so a normal session's log stays readable even when the
     * launcher configures slf4j at DEBUG.
     */
    public static boolean verboseLogs() {
        return source.verboseLogs();
    }

    // ----- input -----------------------------------------------------------

    public static boolean gamepadEnabled() {
        return source.gamepadEnabled();
    }

    public static double gamepadDeadzone() {
        return source.gamepadDeadzone();
    }

    public static double gamepadTriggerThreshold() {
        return source.gamepadTriggerThreshold();
    }

    // ----- helpers ---------------------------------------------------------

    /**
     * {@code null} for empty/blank values, so callers can use the ternary
     * "explicit override, else preset" pattern.
     */
    public static String videoOverride(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * The values the mod reads. Every method has the mod's default, so an
     * implementation only overrides what it actually provides.
     */
    public interface Source {

        default String coresPath() { return "config/retroconsole/cores"; }
        default String romsPath() { return "config/retroconsole/roms"; }
        default String systemPath() { return "config/retroconsole/system"; }
        default String savePath() { return "config/retroconsole/saves"; }
        default String artPath() { return "config/retroconsole/art"; }

        default int videoDistance() { return 48; }
        default int audioDistance() { return 32; }
        default int viewSubscribeDistance() { return 64; }
        default int controlDistance() { return 8; }
        default int notifyDistance() { return 256; }
        default int worldMaxWidth() { return 480; }

        default int maxCoreSlots() { return 0; }
        default int maxPcsx2Sessions() { return 8; }
        default int maxScreenCluster() { return 1024; }
        default int batteryAutosaveSeconds() { return 30; }
        default boolean enable3ds() { return true; }

        default String videoPreset() { return "balanced"; }
        default String flycastInternalResolution() { return ""; }
        default String flycastTexUpscale() { return ""; }
        default String flycastAnisotropic() { return ""; }
        default String ppssppInternalResolution() { return ""; }
        default String ppssppTextureScaling() { return ""; }
        default String pcsx2UpscaleMultiplier() { return ""; }
        default String pcsx2Anisotropic() { return ""; }
        default String citraResolution() { return ""; }
        default String doom3Resolution() { return ""; }
        default boolean doom3InvertY() { return false; }

        default boolean verboseLogs() { return false; }

        default boolean gamepadEnabled() { return true; }
        default double gamepadDeadzone() { return 0.15; }
        default double gamepadTriggerThreshold() { return 0.5; }
    }
}
