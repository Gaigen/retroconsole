package com.retroconsole.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ModConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> CORES_DIR;
    public static final ModConfigSpec.ConfigValue<String> ROMS_DIR;
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_DIR;
    public static final ModConfigSpec.ConfigValue<String> SAVE_DIR;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        CORES_DIR = builder
                .comment("Directory containing libretro core (.so/.dll) files")
                .define("coresDir", "config/retroconsole/cores");

        ROMS_DIR = builder
                .comment("Directory containing ROM files")
                .define("romsDir", "config/retroconsole/roms");

        SYSTEM_DIR = builder
                .comment("Directory for libretro system files (BIOS, etc.)")
                .define("systemDir", "config/retroconsole/system");

        SAVE_DIR = builder
                .comment("Directory for save files (SRAM, save states)")
                .define("saveDir", "config/retroconsole/saves");

        SPEC = builder.build();
    }

    private ModConfig() {}
}
