package com.retroconsole;

/**
 * Loader-agnostic runtime settings. Values default to the
 * NeoForge {@code ModConfig} defaults; each loader entrypoint
 * copies loaded config values into these system properties
 * (see {@code retroconsole.*} keys) before the server logic
 * starts. On NeoForge this happens in the mod constructor;
 * on Fabric, after config is read.
 */
public final class RetroConfig {

    private RetroConfig() {}

    // ----- streaming / distance (defaults from ModConfig) -----

    public static int videoDistance() {
        return intProp("retroconsole.streaming.videoDistance", 48, 8, 256);
    }

    public static int audioDistance() {
        return intProp("retroconsole.streaming.audioDistance", 32, 4, 256);
    }

    public static int viewSubscribeDistance() {
        return intProp("retroconsole.streaming.viewSubscribeDistance", 64, 8, 256);
    }

    public static int controlDistance() {
        return intProp("retroconsole.streaming.controlDistance", 8, 2, 64);
    }

    public static int notifyDistance() {
        return intProp("retroconsole.streaming.notifyDistance", 256, 32, 512);
    }

    public static int worldMaxWidth() {
        return intProp("retroconsole.streaming.worldMaxWidth", 480, 160, 1920);
    }

    // ----- limits -----

    public static int maxCoreSlots() {
        return intProp("retroconsole.limits.maxCoreSlots", 0, 0, 64);
    }

    public static int maxPcsx2Sessions() {
        return intProp("retroconsole.limits.maxPcsx2Sessions", 8, 1, 8);
    }

    public static int maxScreenCluster() {
        return intProp("retroconsole.limits.maxScreenCluster", 1024, 4, 4096);
    }

    public static int batteryAutosaveSeconds() {
        return intProp("retroconsole.limits.batteryAutosaveSeconds", 30, 5, 600);
    }

    // ----- misc -----

    public static boolean enable3ds() {
        String v = System.getProperty("retroconsole.misc.enable3ds");
        return v == null || !v.equalsIgnoreCase("false");
    }

    private static int intProp(String key, int def, int min, int max) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try {
            int parsed = Integer.parseInt(v.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
