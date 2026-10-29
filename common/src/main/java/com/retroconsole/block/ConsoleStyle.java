package com.retroconsole.block;

import net.minecraft.util.StringRepresentable;

/**
 * Chassis designs ported from the RC-01 design lab. Every style is a full model
 * set (off/standby/running) baked into the shared atlas; the style is purely
 * cosmetic and can be cycled on a placed console.
 */
public enum ConsoleStyle implements StringRepresentable {
    CLASSIC("classic"),
    COMPACT("compact"),
    HIFI("hifi"),
    LEGACY("legacy"),
    FULLBLOCK("fullblock");

    private final String name;

    ConsoleStyle(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public ConsoleStyle next() {
        ConsoleStyle[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
