package com.retroconsole.style;

import net.minecraft.util.StringRepresentable;

/**
 * Which plane of the block a screen is fitted to.
 *
 * <p>The shell is authored from the front face inwards, so the same geometry can be
 * pushed to the far side of the block by shifting it along its own normal: a screen
 * flush with the front of a wall, or sunk against the wall behind it. Nothing about
 * the picture changes, only where the hardware sits.
 */
public enum ScreenMount implements StringRepresentable {
    FRONT("front"),
    BACK("back");

    private final String name;

    ScreenMount(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public String translationKey() {
        return "retroconsole.screen.mount." + name;
    }

    public ScreenMount next() {
        ScreenMount[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    /** Model units to add to every shell depth so the body ends on the chosen plane. */
    public float shift(ScreenDesign design) {
        return this == BACK ? 16.0f - design.depth() : 0.0f;
    }
}
