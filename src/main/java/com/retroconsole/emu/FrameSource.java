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

    /** Get current frame width in pixels. */
    int getWidth();

    /** Get current frame height in pixels. */
    int getHeight();
}
