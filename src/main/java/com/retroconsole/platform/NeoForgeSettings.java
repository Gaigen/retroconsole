package com.retroconsole.platform;

import com.retroconsole.config.ModConfig;
import com.retroconsole.config.RetroSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge {@link RetroSettings.Source}: every value comes from the
 * {@code ModConfig} specs.
 *
 * <p>Installed from {@link NeoForgePlatform#init()}, which runs in the
 * {@code @Mod} constructor — after {@code registerConfig}, but the underlying
 * {@code ConfigValue#get()} still throws until ConfigTracker has loaded the
 * file. All reads therefore go through the defensive accessors below.
 */
public final class NeoForgeSettings implements RetroSettings.Source {

    public static final NeoForgeSettings INSTANCE = new NeoForgeSettings();

    private NeoForgeSettings() {}

    // ----- paths -----------------------------------------------------------

    @Override
    public String coresPath() {
        return str(ModConfig.CORES_DIR, "config/retroconsole/cores");
    }

    @Override
    public String romsPath() {
        return str(ModConfig.ROMS_DIR, "config/retroconsole/roms");
    }

    @Override
    public String systemPath() {
        return str(ModConfig.SYSTEM_DIR, "config/retroconsole/system");
    }

    @Override
    public String savePath() {
        return str(ModConfig.SAVE_DIR, "config/retroconsole/saves");
    }

    // ----- streaming / limits ----------------------------------------------

    @Override
    public int videoDistance() {
        return ModConfig.videoDistance();
    }

    @Override
    public int audioDistance() {
        return ModConfig.audioDistance();
    }

    @Override
    public int viewSubscribeDistance() {
        return ModConfig.viewSubscribeDistance();
    }

    @Override
    public int controlDistance() {
        return ModConfig.controlDistance();
    }

    @Override
    public int notifyDistance() {
        return ModConfig.notifyDistance();
    }

    @Override
    public int worldMaxWidth() {
        return ModConfig.worldMaxWidth();
    }

    @Override
    public int maxCoreSlots() {
        return ModConfig.maxCoreSlots();
    }

    @Override
    public int maxPcsx2Sessions() {
        return ModConfig.maxPcsx2Sessions();
    }

    @Override
    public int maxScreenCluster() {
        return ModConfig.maxScreenCluster();
    }

    @Override
    public int batteryAutosaveSeconds() {
        return ModConfig.batteryAutosaveSeconds();
    }

    @Override
    public boolean enable3ds() {
        return ModConfig.enable3ds();
    }

    // ----- video -----------------------------------------------------------

    @Override
    public String videoPreset() {
        return ModConfig.videoPreset();
    }

    @Override
    public String flycastInternalResolution() {
        return raw(ModConfig.FLYCAST_INTERNAL_RESOLUTION);
    }

    @Override
    public String flycastTexUpscale() {
        return raw(ModConfig.FLYCAST_TEX_UPSCALE);
    }

    @Override
    public String flycastAnisotropic() {
        return raw(ModConfig.FLYCAST_ANISOTROPIC);
    }

    @Override
    public String ppssppInternalResolution() {
        return raw(ModConfig.PPSSPP_INTERNAL_RESOLUTION);
    }

    @Override
    public String ppssppTextureScaling() {
        return raw(ModConfig.PPSSPP_TEXTURE_SCALING);
    }

    @Override
    public String pcsx2UpscaleMultiplier() {
        return raw(ModConfig.PCSX2_UPSCALE_MULTIPLIER);
    }

    @Override
    public String pcsx2Anisotropic() {
        return raw(ModConfig.PCSX2_ANISOTROPIC);
    }

    @Override
    public String citraResolution() {
        return raw(ModConfig.CITRA_RESOLUTION);
    }

    @Override
    public String doom3Resolution() {
        return raw(ModConfig.DOOM3_RESOLUTION);
    }

    @Override
    public boolean doom3InvertY() {
        return ModConfig.doom3InvertY();
    }

    // ----- input -----------------------------------------------------------

    @Override
    public boolean gamepadEnabled() {
        return ModConfig.gamepadEnabled();
    }

    @Override
    public double gamepadDeadzone() {
        return ModConfig.gamepadDeadzone();
    }

    @Override
    public double gamepadTriggerThreshold() {
        return ModConfig.gamepadTriggerThreshold();
    }

    // ----- helpers ---------------------------------------------------------

    /** Path-ish value; blank or unreadable falls back to the default. */
    private static String str(ModConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            String s = value.get();
            return s == null || s.isBlank() ? fallback : s;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Video override; blank means "use the preset", so blank is preserved. */
    private static String raw(ModConfigSpec.ConfigValue<String> value) {
        try {
            String s = value.get();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }
}
