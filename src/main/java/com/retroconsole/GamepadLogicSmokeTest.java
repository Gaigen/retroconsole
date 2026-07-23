package com.retroconsole;

import com.retroconsole.bridge.LibretroBridge;
import com.retroconsole.client.input.GamepadMapping;
import org.lwjgl.glfw.GLFW;

/** Smoke test for mapping + deadzone. Pure logic, no GLFW init, no device needed. */
public final class GamepadLogicSmokeTest {

    public static void main(String[] args) {
        // 1) deadzone: inside -> (0,0)
        short[] r = GamepadMapping.applyDeadzone(0.05f, 0.05f, 0.15);
        assertEq(r[0], 0); assertEq(r[1], 0);

        // 2) full deflection -> full range
        r = GamepadMapping.applyDeadzone(1f, 0f, 0.15);
        assertEq(r[0], 32767); assertEq(r[1], 0);

        // 3) just above deadzone -> small nonzero (rescale starts from 0, no jump)
        r = GamepadMapping.applyDeadzone(0.16f, 0f, 0.15);
        if (r[0] <= 0 || r[0] > 2000) throw new AssertionError("rescale broken: " + r[0]);

        // 4) trigger normalization: resting -1 -> 0, full 1 -> 1
        assertEq(Math.round(GamepadMapping.normalizeTrigger(-1f) * 100), 0);
        assertEq(Math.round(GamepadMapping.normalizeTrigger(1f) * 100), 100);

        // 5) positional face buttons: Xbox A -> RetroPad B, Xbox B -> RetroPad A
        assertEq(retroFor(GLFW.GLFW_GAMEPAD_BUTTON_A), LibretroBridge.RETRO_DEVICE_ID_JOYPAD_B);
        assertEq(retroFor(GLFW.GLFW_GAMEPAD_BUTTON_B), LibretroBridge.RETRO_DEVICE_ID_JOYPAD_A);
        assertEq(retroFor(GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP), LibretroBridge.RETRO_DEVICE_ID_JOYPAD_UP);

        // 6) no duplicate retro ids in the table
        boolean[] seen = new boolean[32];
        for (GamepadMapping.Entry e : GamepadMapping.BUTTONS) {
            if (seen[e.retroId()]) throw new AssertionError("duplicate retro id " + e.retroId());
            seen[e.retroId()] = true;
        }

        System.out.println("GamepadLogicSmokeTest OK");
    }

    private static int retroFor(int glfwButton) {
        for (GamepadMapping.Entry e : GamepadMapping.BUTTONS) {
            if (e.glfwButton() == glfwButton) return e.retroId();
        }
        return -1;
    }

    private static void assertEq(int a, int b) {
        if (a != b) throw new AssertionError(a + " != " + b);
    }
}
