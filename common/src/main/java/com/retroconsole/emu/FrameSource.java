package com.retroconsole.emu;

/**
 * Common interface for all emulator backends.
 * Each emulator must provide a frame buffer and dimensions.
 */
public interface FrameSource {
    /** Execute one frame of emulation. */
    void runFrame();

    /** Get the current frame buffer (int[] ARGB). */
    int[] getFrameBuffer();

    /**
     * Pull the newest frame waiting in the source into {@code dst}.
     *
     * <p>Overridden by sources that can copy straight out of their own buffer —
     * the default goes through {@link #getFrameBuffer()} and costs one extra
     * full-frame copy per call. Returning false means "no frame ready", not
     * "error": the caller simply tries again on the next tick.
     */
    default boolean pollFrame(int[] dst) {
        int[] src = getFrameBuffer();
        if (src == null || src.length == 0 || dst == null || dst.length == 0) return false;
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
        return true;
    }

    /** Get current frame width in pixels. */
    int getWidth();

    /** Get current frame height in pixels. */
    int getHeight();

    /** Native frame rate for pacing (defaults to 60 Hz). */
    default double getTimingFps() {
        return 60.0;
    }
}
