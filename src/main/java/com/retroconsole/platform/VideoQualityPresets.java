package com.retroconsole.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/** Default libretro core options for ~1920px-wide output and max visual quality. */
public final class VideoQualityPresets {

    private VideoQualityPresets() {}

    public static Map<String, String> flycastOverrides() {
        Map<String, String> m = new LinkedHashMap<>();
        putBoth(m, "threaded_rendering", "disabled");
        putBoth(m, "per_content_vmus", "VMU A1");
        putBoth(m, "device_port1_slot1", "VMU");
        putBoth(m, "internal_resolution", "1920x1440");
        putBoth(m, "texupscale", "2");
        putBoth(m, "texupscale_max_filtered_texture_size", "1024");
        putBoth(m, "anisotropic_filtering", "8");
        putBoth(m, "texture_filtering", "1");
        putBoth(m, "alpha_sorting", "per-triangle (normal)");
        putBoth(m, "fix_upscale_bleeding_edge", "enabled");
        return Map.copyOf(m);
    }

    /** 2× native (960×544) — 1920×1088 is ~16× pixels + GL readback and lags badly. */
    public static Map<String, String> ppssppOverrides() {
        return Map.of(
                "ppsspp_cpu_core", "JIT",
                "ppsspp_fast_memory", "disabled",
                "ppsspp_frameskip", "0",
                "ppsspp_auto_frameskip", "disabled",
                "ppsspp_internal_resolution", "960x544",
                "ppsspp_texture_scaling_level", "2",
                "ppsspp_texture_shader", "xBRZ");
    }

    /** OpenGL 3× (~1920×1344) — works with headless WGL/EGL readback. */
    public static Map<String, String> pcsx2Overrides() {
        return Map.of(
                "pcsx2_fastboot", "enabled",
                "pcsx2_renderer", "OpenGL",
                "pcsx2_upscale_multiplier", "2",
                "pcsx2_anisotropic_filtering", "16",
                "pcsx2_trilinear_filtering", "Trilinear (Forced)",
                "pcsx2_blending_accuracy", "Basic");
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

    private static void putBoth(Map<String, String> m, String suffix, String value) {
        m.put("flycast_" + suffix, value);
        m.put("reicast_" + suffix, value);
    }
}
