package com.retroconsole.client.input;

/**
 * Maps TvScreen pixel coordinates inside the game frame rect to libretro POINTER coords.
 * libretro: (-32767,-32767) = top-left of core framebuffer, (32767,32767) = bottom-right.
 */
public final class PointerMapper {

    private static final int MIN = -32767;
    private static final int MAX = 32767;

    private PointerMapper() {}

    public record LibretroPointer(short x, short y) {}

    public static LibretroPointer fromScreen(double mouseX, double mouseY,
                                             int frameX, int frameY, int frameW, int frameH) {
        if (frameW <= 0 || frameH <= 0) return new LibretroPointer((short) 0, (short) 0);
        double nx = clamp01((mouseX - frameX) / frameW);
        double ny = clamp01((mouseY - frameY) / frameH);
        short x = (short) Math.round(nx * (MAX - MIN) + MIN);
        short y = (short) Math.round(ny * (MAX - MIN) + MIN);
        return new LibretroPointer(x, y);
    }

    public static LibretroPointer move(LibretroPointer current, int dx, int dy, int step) {
        int x = clamp(current.x + dx * step, MIN, MAX);
        int y = clamp(current.y + dy * step, MIN, MAX);
        return new LibretroPointer((short) x, (short) y);
    }

    public static boolean insideFrame(double mouseX, double mouseY,
                                      int frameX, int frameY, int frameW, int frameH) {
        return mouseX >= frameX && mouseX < frameX + frameW
                && mouseY >= frameY && mouseY < frameY + frameH;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
