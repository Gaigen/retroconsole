package com.retroconsole.client.input;

import com.retroconsole.bridge.LibretroBridge;
import org.lwjgl.glfw.GLFW;

/**
 * Single source of truth: GLFW gamepad (standard Xbox layout) -> RetroPad.
 *
 * Positional face-button mapping (RetroArch default):
 *   Xbox A (south) -> RetroPad B,  Xbox B (east)  -> RetroPad A,
 *   Xbox X (west)  -> RetroPad Y,  Xbox Y (north) -> RetroPad X.
 * Any pad recognized by GLFW's bundled SDL_GameControllerDB (Xbox/PS/Switch/
 * most USB pads) is already normalized to this layout, so one table covers all.
 *
 * Pure logic, no GLFW window/context calls -> testable without a device.
 */
public final class GamepadMapping {

    public record Entry(int glfwButton, int retroId) {}

    public static final Entry[] BUTTONS = {
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_A, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_B),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_B, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_A),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_X, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_Y),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_Y, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_X),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_BACK, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_SELECT),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_START, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_START),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_THUMB, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L3),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_RIGHT_THUMB, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R3),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_UP),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_DOWN),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_LEFT),
            new Entry(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_RIGHT),
    };

    private GamepadMapping() {}

    /** GLFW trigger axis (-1..1, resting -1) -> normalized 0..1. */
    public static float normalizeTrigger(float axisValue) {
        float v = (axisValue + 1.0f) * 0.5f;
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /**
     * Radial deadzone with rescale: below deadzone -> (0,0); above -> rescaled
     * so output starts from 0 at the deadzone edge (no value jump).
     * Returns {x, y} in libretro range -32768..32767.
     */
    public static short[] applyDeadzone(float x, float y, double deadzone) {
        double mag = Math.sqrt(x * (double) x + y * (double) y);
        if (mag <= deadzone || mag == 0.0) {
            return new short[] {0, 0};
        }
        double scale = Math.min(1.0, (mag - deadzone) / (1.0 - deadzone)) / mag;
        return new short[] {toRetroAxis(x * scale), toRetroAxis(y * scale)};
    }

    /** Clamp -1..1 float to libretro short range. */
    public static short toRetroAxis(double v) {
        long r = Math.round(v * 32767.0);
        if (r < -32768) r = -32768;
        if (r > 32767) r = 32767;
        return (short) r;
    }
}
