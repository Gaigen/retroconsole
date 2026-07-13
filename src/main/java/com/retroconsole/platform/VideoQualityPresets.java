package com.retroconsole.platform;

import com.retroconsole.config.ModConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HW-core video options. Base values come from {@link ModConfig#videoPreset()};
 * non-empty fields in {@code [video]} of the toml override individual knobs.
 */
public final class VideoQualityPresets {

    private VideoQualityPresets() {}

    public static Map<String, String> flycastOverrides() {
        Map<String, String> m = new LinkedHashMap<>();
        Preset p = Preset.fromConfig();
        putBoth(m, "threaded_rendering", "disabled");
        putBoth(m, "per_content_vmus", "VMU A1");
        putBoth(m, "device_port1_slot1", "VMU");
        putBoth(m, "internal_resolution",
                override(ModConfig.FLYCAST_INTERNAL_RESOLUTION, p.flycastRes));
        putBoth(m, "texupscale",
                override(ModConfig.FLYCAST_TEX_UPSCALE, p.flycastTex));
        putBoth(m, "texupscale_max_filtered_texture_size", "1024");
        putBoth(m, "anisotropic_filtering",
                override(ModConfig.FLYCAST_ANISOTROPIC, p.flycastAniso));
        putBoth(m, "texture_filtering", "1");
        putBoth(m, "alpha_sorting", "per-triangle (normal)");
        putBoth(m, "fix_upscale_bleeding_edge", "enabled");
        return Map.copyOf(m);
    }

    public static Map<String, String> ppssppOverrides() {
        Preset p = Preset.fromConfig();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ppsspp_cpu_core", "JIT");
        m.put("ppsspp_fast_memory", "disabled");
        m.put("ppsspp_frameskip", "0");
        m.put("ppsspp_auto_frameskip", "disabled");
        m.put("ppsspp_internal_resolution",
                override(ModConfig.PPSSPP_INTERNAL_RESOLUTION, p.ppssppRes));
        m.put("ppsspp_texture_scaling_level",
                override(ModConfig.PPSSPP_TEXTURE_SCALING, p.ppssppTex));
        m.put("ppsspp_texture_shader", "xBRZ");
        return Map.copyOf(m);
    }

    public static Map<String, String> pcsx2Overrides() {
        Preset p = Preset.fromConfig();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pcsx2_fastboot", "enabled");
        m.put("pcsx2_renderer", "OpenGL");
        m.put("pcsx2_upscale_multiplier",
                override(ModConfig.PCSX2_UPSCALE_MULTIPLIER, p.pcsx2Scale));
        m.put("pcsx2_anisotropic_filtering",
                override(ModConfig.PCSX2_ANISOTROPIC, p.pcsx2Aniso));
        m.put("pcsx2_trilinear_filtering", "Trilinear (Forced)");
        m.put("pcsx2_blending_accuracy", "Basic");
        return Map.copyOf(m);
    }

    public static Map<String, String> melondsOverrides() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("melonds_screen_layout", "Top/Bottom");
        m.put("melonds_renderer", "software");
        m.put("melonds_show_cursor", "enabled");
        // Classic melonds_libretro: disabled | Mouse | Touch | Joystick
        m.put("melonds_touch_mode", "Touch");
        return Map.copyOf(m);
    }

    public static Map<String, String> citraOverrides() {
        Preset p = Preset.fromConfig();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("citra_renderer", "OpenGL");
        m.put("citra_layout_option", "Default Top-Bottom Screen");
        m.put("citra_swap_screen", "Top");
        m.put("citra_resolution_factor",
                citraResValue(override(ModConfig.CITRA_RESOLUTION, p.citraRes)));
        m.put("citra_use_acc_mul", "enabled");
        m.put("citra_use_hw_shader_cache", "disabled");
        // Touch: client sends absolute RETRO_DEVICE_POINTER, not relative MOUSE
        m.put("citra_touch_touchscreen", "enabled");
        m.put("citra_mouse_touchscreen", "disabled");
        m.put("citra_render_touchscreen", "enabled");
        return Map.copyOf(m);
    }

    private static String citraResValue(String raw) {
        if (raw == null || raw.isBlank()) return "1x (Native)";
        String s = raw.trim();
        if (s.equals("1") || s.equalsIgnoreCase("1x")) return "1x (Native)";
        return s.endsWith("x") || s.contains("(") ? s : s + "x";
    }

    public static String flycastDefault(String key) {
        return flycastOverrides().get(key);
    }

    public static String ppssppDefault(String key) {
        return ppssppOverrides().get(key);
    }

    public static String pcsx2Default(String key) {
        String preset = pcsx2Overrides().get(key);
        if (preset != null) return preset;
        return switch (key) {
            case "pcsx2_analog_mode1" -> "enabled";
            case "pcsx2_fastcdvd" -> "enabled";
            case "pcsx2_enable_hw_hacks" -> "disabled";
            case "pcsx2_hw_download_mode" -> "Accurate";
            default -> null;
        };
    }

    public static String pcsxRearmedDefault(String key) {
        return switch (key) {
            case "pcsx_rearmed_memcard1" -> "libretro";
            case "pcsx_rearmed_memcard2" -> "shared";
            case "pcsx_rearmed_neon_enhancement_enable" -> "enabled";
            case "pcsx_rearmed_gpu_unai_scale_hires" -> "enabled";
            default -> null;
        };
    }

    public static void seedFlycast(java.util.function.BiConsumer<String, String> register) {
        flycastOverrides().forEach(register);
    }

    public static void seedPpsspp(java.util.function.BiConsumer<String, String> register) {
        ppssppOverrides().forEach(register);
        register.accept("ppsspp_backend", "opengl");
        register.accept("ppsspp_mulitsample_level", "Disabled");
        register.accept("ppsspp_skip_buffer_effects", "disabled");
        register.accept("ppsspp_skip_gpu_readbacks", "disabled");
        register.accept("ppsspp_lazy_texture_caching", "enabled");
        register.accept("ppsspp_gpu_hardware_transform", "enabled");
        register.accept("ppsspp_inflight_frames", "Up to 1");
    }

    public static void seedPcsx2(java.util.function.BiConsumer<String, String> register) {
        pcsx2Overrides().forEach(register);
        register.accept("pcsx2_analog_mode1", "enabled");
        register.accept("pcsx2_fastcdvd", "enabled");
        register.accept("pcsx2_enable_hw_hacks", "disabled");
        register.accept("pcsx2_hw_download_mode", "Accurate");
    }

    public static void seedMelonds(java.util.function.BiConsumer<String, String> register) {
        melondsOverrides().forEach(register);
    }

    public static void seedCitra(java.util.function.BiConsumer<String, String> register) {
        citraOverrides().forEach(register);
    }

    private static String override(
            net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String> value, String preset) {
        String o = ModConfig.videoOverride(value);
        return o != null ? o : preset;
    }

    private record Preset(
            String flycastRes, String flycastTex, String flycastAniso,
            String ppssppRes, String ppssppTex,
            String pcsx2Scale, String pcsx2Aniso,
            String citraRes
    ) {
        static Preset fromConfig() {
            return switch (ModConfig.videoPreset()) {
                case "performance" -> new Preset(
                        "640x480", "1", "1",
                        "480x272", "1",
                        "1", "2",
                        "1x (Native)");
                case "quality" -> new Preset(
                        "1920x1440", "2", "16",
                        "1440x816", "3",
                        "3", "16",
                        "3x");
                default -> new Preset( // balanced
                        "1280x960", "2", "8",
                        "960x544", "2",
                        "2", "16",
                        "2x");
            };
        }
    }

    private static void putBoth(Map<String, String> m, String suffix, String value) {
        m.put("flycast_" + suffix, value);
        m.put("reicast_" + suffix, value);
    }
}
