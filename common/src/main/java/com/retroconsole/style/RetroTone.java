package com.retroconsole.style;

import com.retroconsole.client.ChassisPalette;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Colour finish of a chassis (console, screen, gamepad).
 *
 * <p>A tone never changes geometry: it is the colour a shell's body faces are
 * painted with, so cream, graphite and lavender all come out of one shape.
 * Consoles bake a palette atlas per tone, the screen renderer colours the body
 * from code, gamepads ship one baked model per tone. The values are the body
 * colours of the console atlases, so a console and a screen of one finish match.
 */
public enum RetroTone implements StringRepresentable {
    CREAM("cream", 0xD7CFB9),
    GRAPHITE("graphite", 0x3B414D),
    LAVENDER("lavender", 0xB9A2D8);

    private final String name;
    /** Body colour in 0xRRGGBB. */
    private final int rgb;

    RetroTone(String name, int rgb) {
        this.name = name;
        this.rgb = rgb;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Key naming the finish in tooltips and action-bar feedback. */
    public String translationKey() {
        return "retroconsole.tone." + name;
    }

    public int bodyRgb() {
        return rgb;
    }

    /** Body colour as 0..1 channels, ready for a vertex colour. */
    public float[] bodyChannels() {
        return ChassisPalette.channels(rgb);
    }

    public RetroTone next() {
        RetroTone[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    @Nullable
    public static RetroTone ofStack(ItemStack stack) {
        return stack.getItem() instanceof DyeItem dye ? ofDye(dye.getDyeColor()) : null;
    }

    /** Dye that paints this tone, or null when the dye is not a chassis colour. */
    @Nullable
    public static RetroTone ofDye(@Nullable DyeColor color) {
        if (color == null) return null;
        return switch (color) {
            case WHITE -> CREAM;
            case LIGHT_GRAY, GRAY, BLACK -> GRAPHITE;
            case PURPLE, MAGENTA, PINK -> LAVENDER;
            default -> null;
        };
    }
}
