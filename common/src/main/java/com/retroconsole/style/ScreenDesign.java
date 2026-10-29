package com.retroconsole.style;

import net.minecraft.util.StringRepresentable;

/**
 * Screen shell family ported from the RC display lab. Every design shares one
 * rule set (rear box, perimeter ring, chin, recessed lip) and differs only in
 * depth, bezel width and the points where the picture starts.
 *
 * <p>All values are model units (0..16 per block, one unit = 1/16 block) and
 * describe the front face: {@code depth} is how far the body reaches behind it,
 * {@code bezel}/{@code bottom} are the margins kept around the video, and
 * {@code videoZ} is how deep the picture sits behind the front lip. Half a unit
 * is the lab contract; the shell keeps every other surface off that plane, so
 * the picture does not need to hide deeper.
 */
public enum ScreenDesign implements StringRepresentable {
    /** Warm cream CRT: deep body, wide bezel, chin with speaker slots. */
    RETRO("retro", 14.0f, 1.5f, 2.5f, 0.5f),
    /** Slim graphite monitor: thin bezel and a colour accent strip. */
    STUDIO("studio", 4.0f, 0.75f, 1.125f, 0.5f),
    /** Modular panel: minimal dark frame, maximum picture area. */
    WALL("wall", 6.0f, 0.5f, 0.75f, 0.5f),
    /** Frameless front: the whole face is the picture, only a 2-unit body behind. */
    EDGE("edge", 2.0f, 0.0f, 0.0f, 0.0625f),
    /** Frameless front on a full block: the same face, the body filling the cube. */
    SOLID("solid", 16.0f, 0.0f, 0.0f, 0.0625f);

    private final String name;
    private final float depth;
    private final float bezel;
    private final float bottom;
    private final float videoZ;

    ScreenDesign(String name, float depth, float bezel, float bottom, float videoZ) {
        this.name = name;
        this.depth = depth;
        this.bezel = bezel;
        this.bottom = bottom;
        this.videoZ = videoZ;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Key naming the shell in tooltips and action-bar feedback. */
    public String translationKey() {
        return "retroconsole.screen.design." + name;
    }

    public float depth() {
        return depth;
    }

    public float bezel() {
        return bezel;
    }

    public float bottom() {
        return bottom;
    }

    public float videoZ() {
        return videoZ;
    }

    public ScreenDesign next() {
        ScreenDesign[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
