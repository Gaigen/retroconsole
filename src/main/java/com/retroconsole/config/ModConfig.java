package com.retroconsole.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Locale;

/**
 * Paths live in {@code retroconsole-common.toml} (always editable).
 * Streaming / limits / video live in {@code retroconsole-server.toml}
 * (editable in-game on singleplayer / LAN host; on a dedicated server edit the
 * server file).
 */
public final class ModConfig {

    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.ConfigValue<String> CORES_DIR;
    public static final ModConfigSpec.ConfigValue<String> ROMS_DIR;
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_DIR;
    public static final ModConfigSpec.ConfigValue<String> SAVE_DIR;

    public static final ModConfigSpec.IntValue VIDEO_DISTANCE;
    public static final ModConfigSpec.IntValue AUDIO_DISTANCE;
    public static final ModConfigSpec.IntValue VIEW_SUBSCRIBE_DISTANCE;
    public static final ModConfigSpec.IntValue CONTROL_DISTANCE;
    public static final ModConfigSpec.IntValue NOTIFY_DISTANCE;
    public static final ModConfigSpec.IntValue WORLD_MAX_WIDTH;

    public static final ModConfigSpec.IntValue MAX_CORE_SLOTS;
    public static final ModConfigSpec.IntValue MAX_PCSX2_SESSIONS;
    public static final ModConfigSpec.IntValue MAX_SCREEN_CLUSTER;
    public static final ModConfigSpec.IntValue BATTERY_AUTOSAVE_SECONDS;

    public static final ModConfigSpec.ConfigValue<String> VIDEO_PRESET;
    public static final ModConfigSpec.ConfigValue<String> FLYCAST_INTERNAL_RESOLUTION;
    public static final ModConfigSpec.ConfigValue<String> FLYCAST_TEX_UPSCALE;
    public static final ModConfigSpec.ConfigValue<String> FLYCAST_ANISOTROPIC;
    public static final ModConfigSpec.ConfigValue<String> PPSSPP_INTERNAL_RESOLUTION;
    public static final ModConfigSpec.ConfigValue<String> PPSSPP_TEXTURE_SCALING;
    public static final ModConfigSpec.ConfigValue<String> PCSX2_UPSCALE_MULTIPLIER;
    public static final ModConfigSpec.ConfigValue<String> PCSX2_ANISOTROPIC;

    private static final String T = "retroconsole.configuration.";

    static {
        ModConfigSpec.Builder common = new ModConfigSpec.Builder();
        common.translation(T + "paths").push("paths");
        CORES_DIR = common
                .comment("Directory containing libretro core (.so/.dll) files")
                .translation(T + "coresDir")
                .define("coresDir", "config/retroconsole/cores");
        ROMS_DIR = common
                .comment("Directory containing ROM files")
                .translation(T + "romsDir")
                .define("romsDir", "config/retroconsole/roms");
        SYSTEM_DIR = common
                .comment("Directory for libretro system files (BIOS, etc.)")
                .translation(T + "systemDir")
                .define("systemDir", "config/retroconsole/system");
        SAVE_DIR = common
                .comment("Directory for save files (SRAM, save states)")
                .translation(T + "saveDir")
                .define("saveDir", "config/retroconsole/saves");
        common.pop();
        COMMON_SPEC = common.build();

        ModConfigSpec.Builder server = new ModConfigSpec.Builder();

        server.translation(T + "streaming").push("streaming");
        VIDEO_DISTANCE = server
                .comment("Blocks from the console: players in range receive in-world TV video frames.")
                .translation(T + "videoDistance")
                .defineInRange("videoDistance", 48, 8, 256);
        AUDIO_DISTANCE = server
                .comment("Blocks from the console: players in range receive console audio.")
                .translation(T + "audioDistance")
                .defineInRange("audioDistance", 32, 4, 256);
        VIEW_SUBSCRIBE_DISTANCE = server
                .comment("Max distance to subscribe to fullscreen TV.")
                .translation(T + "viewSubscribeDistance")
                .defineInRange("viewSubscribeDistance", 64, 8, 256);
        CONTROL_DISTANCE = server
                .comment("Max distance to send input, save states, power off, open library.")
                .translation(T + "controlDistance")
                .defineInRange("controlDistance", 8, 2, 64);
        NOTIFY_DISTANCE = server
                .comment("Radius for stop-console notifications.")
                .translation(T + "notifyDistance")
                .defineInRange("notifyDistance", 256, 32, 512);
        WORLD_MAX_WIDTH = server
                .comment("Max frame width for in-world screen (not fullscreen TV). Lower = less bandwidth.")
                .translation(T + "worldMaxWidth")
                .defineInRange("worldMaxWidth", 480, 160, 1920);
        server.pop();

        server.translation(T + "limits").push("limits");
        MAX_CORE_SLOTS = server
                .comment("Max simultaneous DLL/SO copies per core (0 = unlimited).")
                .translation(T + "maxCoreSlots")
                .defineInRange("maxCoreSlots", 0, 0, 64);
        MAX_PCSX2_SESSIONS = server
                .comment("Max concurrent PS2 (LRPS2) sessions (hard cap 8).")
                .translation(T + "maxPcsx2Sessions")
                .defineInRange("maxPcsx2Sessions", 8, 1, 8);
        MAX_SCREEN_CLUSTER = server
                .comment("Max Screen blocks in one coplanar flood-fill.")
                .translation(T + "maxScreenCluster")
                .defineInRange("maxScreenCluster", 1024, 4, 4096);
        BATTERY_AUTOSAVE_SECONDS = server
                .comment("How often running cores flush battery/SRAM saves (seconds).")
                .translation(T + "batteryAutosaveSeconds")
                .defineInRange("batteryAutosaveSeconds", 30, 5, 600);
        server.pop();

        server.translation(T + "video").push("video");
        VIDEO_PRESET = server
                .comment("Base quality for HW cores: performance | balanced | quality. Non-empty fields below override the preset.")
                .translation(T + "preset")
                .define("preset", "balanced");
        FLYCAST_INTERNAL_RESOLUTION = server
                .comment("Flycast internal_resolution, e.g. 640x480, 1280x960, 1920x1440. Empty = preset.")
                .translation(T + "flycastInternalResolution")
                .define("flycastInternalResolution", "");
        FLYCAST_TEX_UPSCALE = server
                .comment("Flycast texupscale (1, 2, …). Empty = preset.")
                .translation(T + "flycastTexUpscale")
                .define("flycastTexUpscale", "");
        FLYCAST_ANISOTROPIC = server
                .comment("Flycast anisotropic_filtering (1–16). Empty = preset.")
                .translation(T + "flycastAnisotropic")
                .define("flycastAnisotropic", "");
        PPSSPP_INTERNAL_RESOLUTION = server
                .comment("PPSSPP resolution, e.g. 480x272, 960x544, 1440x816. Empty = preset.")
                .translation(T + "ppssppInternalResolution")
                .define("ppssppInternalResolution", "");
        PPSSPP_TEXTURE_SCALING = server
                .comment("PPSSPP texture_scaling_level (1, 2, …). Empty = preset.")
                .translation(T + "ppssppTextureScaling")
                .define("ppssppTextureScaling", "");
        PCSX2_UPSCALE_MULTIPLIER = server
                .comment("PCSX2 upscale_multiplier (1–6). Empty = preset.")
                .translation(T + "pcsx2UpscaleMultiplier")
                .define("pcsx2UpscaleMultiplier", "");
        PCSX2_ANISOTROPIC = server
                .comment("PCSX2 anisotropic_filtering (2–16). Empty = preset.")
                .translation(T + "pcsx2Anisotropic")
                .define("pcsx2Anisotropic", "");
        server.pop();

        SERVER_SPEC = server.build();
    }

    @Deprecated
    public static final ModConfigSpec SPEC = COMMON_SPEC;

    public static int videoDistance() {
        return safe(VIDEO_DISTANCE, 48);
    }

    public static int audioDistance() {
        return safe(AUDIO_DISTANCE, 32);
    }

    public static int viewSubscribeDistance() {
        return safe(VIEW_SUBSCRIBE_DISTANCE, 64);
    }

    public static int controlDistance() {
        return safe(CONTROL_DISTANCE, 8);
    }

    public static int notifyDistance() {
        return safe(NOTIFY_DISTANCE, 256);
    }

    public static int worldMaxWidth() {
        return safe(WORLD_MAX_WIDTH, 480);
    }

    public static int maxCoreSlots() {
        return safe(MAX_CORE_SLOTS, 0);
    }

    public static int maxPcsx2Sessions() {
        return safe(MAX_PCSX2_SESSIONS, 8);
    }

    public static int maxScreenCluster() {
        return safe(MAX_SCREEN_CLUSTER, 1024);
    }

    public static int batteryAutosaveSeconds() {
        return safe(BATTERY_AUTOSAVE_SECONDS, 30);
    }

    public static String videoPreset() {
        try {
            String p = VIDEO_PRESET.get();
            if (p == null || p.isBlank()) return "balanced";
            return p.trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "balanced";
        }
    }

    public static String videoOverride(ModConfigSpec.ConfigValue<String> value) {
        try {
            String s = value.get();
            if (s == null) return null;
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static int safe(ModConfigSpec.IntValue v, int fallback) {
        try {
            return v.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private ModConfig() {}
}
