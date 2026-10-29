package com.retroconsole.style;

import net.minecraft.util.StringRepresentable;

/**
 * Gamepad silhouette from the display lab. Each shape ships one model per
 * {@link RetroTone}; the model index is {@code design.ordinal() * 3 + tone.ordinal()}
 * and is carried by the stack's {@code custom_model_data}.
 */
public enum PadDesign implements StringRepresentable {
    SOFT("soft"),
    BRICK("brick"),
    GRIP("grip");

    private final String name;

    PadDesign(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public PadDesign next() {
        PadDesign[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
