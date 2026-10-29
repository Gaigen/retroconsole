package com.retroconsole.client;

import net.minecraft.resources.Identifier;

/**
 * The flat colours of a display chassis, plus the blank texture that carries them.
 *
 * <p>The lab paints every face from one shared atlas; the screen renderer cannot,
 * because shell pieces are long thin strips and a strip seen edge-on minifies its
 * swatch cell so hard that the mips pull in the cream background around it — the
 * frame then glows and tears instead of reading as a solid edge. So the renderer
 * draws on a plain white texture and puts the colour in the vertex, which is
 * exact at any angle and any distance.
 *
 * <p>Values are the lab's {@code COLORS} table; the console atlases are
 * recolourings of the same palette, so a console and a screen of one finish line up.
 */
public final class ChassisPalette {

    /** Uniform white image; the vertex colour does all the work. */
    public static final Identifier PLAIN =
            Identifier.fromNamespaceAndPath("retroconsole", "textures/block/chassis_plain.png");

    public static final int CREAM = 0xD7CFB9;
    public static final int SHADE = 0xAAA390;
    public static final int LIGHT = 0xE8DFC8;
    public static final int DARK = 0x343943;
    public static final int RUBBER = 0x232731;
    public static final int METAL = 0x858B8C;
    public static final int TEAL = 0x589C98;
    public static final int MINT = 0x8FD8AD;
    public static final int CORAL = 0xDA725B;
    public static final int RED = 0xA74C43;
    public static final int PURPLE = 0xB9A2D8;
    public static final int PURPLE_DARK = 0x77638E;
    public static final int PURPLE_LIGHT = 0xD8C8EA;
    public static final int GOLD = 0xC6A271;
    public static final int BLACK = 0x11191E;
    public static final int WHITE = 0xEEE5CF;

    private ChassisPalette() {}

    /** Colour as 0..1 channels, ready for a vertex colour. */
    public static float[] channels(int rgb) {
        return new float[]{
                ((rgb >> 16) & 0xFF) / 255.0f,
                ((rgb >> 8) & 0xFF) / 255.0f,
                (rgb & 0xFF) / 255.0f
        };
    }
}
