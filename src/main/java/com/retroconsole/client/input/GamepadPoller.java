package com.retroconsole.client.input;

import com.retroconsole.bridge.LibretroBridge;
import com.retroconsole.config.ModConfig;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the first connected GLFW gamepad and feeds RetroInputSender —
 * the exact path keyboard input takes (packets are sent only on change).
 *
 * THREADING: poll() MUST run on the client main thread (Screen.tick()) —
 * GLFW joystick functions are main-thread-only.
 * Lifecycle is tied to the screen: create in ctor, poll() in tick(),
 * reset() in onClose()/removed().
 */
public final class GamepadPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(GamepadPoller.class);

    private final RetroInputSender input;
    private final GLFWGamepadState state = GLFWGamepadState.create(); // BufferUtils-backed, GC-managed
    private final boolean[] down = new boolean[32]; // retro ids 0..20 fit
    private int jid = -1;
    private boolean anyActive;

    public GamepadPoller(RetroInputSender input) {
        this.input = input;
    }

    /** Call once per client tick while the console screen is open. */
    public void poll() {
        if (!ModConfig.gamepadEnabled()) {
            reset();
            return;
        }
        int pad = findGamepad();
        if (pad < 0 || !GLFW.glfwGetGamepadState(pad, state)) {
            reset(); // handles hot-unplug: releases everything we pressed
            return;
        }

        // Digital buttons (GLFW already normalized any pad to the standard layout).
        for (GamepadMapping.Entry e : GamepadMapping.BUTTONS) {
            setButton(e.retroId(), state.buttons(e.glfwButton()) == GLFW.GLFW_PRESS);
        }

        // Analog triggers -> digital L2/R2.
        float threshold = (float) ModConfig.gamepadTriggerThreshold();
        setButton(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2,
                GamepadMapping.normalizeTrigger(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_TRIGGER)) >= threshold);
        setButton(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2,
                GamepadMapping.normalizeTrigger(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)) >= threshold);

        // Sticks -> real RETRO_DEVICE_ANALOG axes (PSP/Dreamcast/3DS work natively).
        double dz = ModConfig.gamepadDeadzone();
        short[] left = GamepadMapping.applyDeadzone(
                state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X),
                state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y), dz);
        short[] right = GamepadMapping.applyDeadzone(
                state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X),
                state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y), dz);
        input.setGamepadAnalog(left[0], left[1], right[0], right[1]);
        anyActive = true;
    }

    /** Release everything this poller pressed; idempotent. */
    public void reset() {
        if (!anyActive && jid < 0) return;
        for (int id = 0; id < down.length; id++) {
            if (down[id]) {
                down[id] = false;
                input.sendButton(id, false);
            }
        }
        input.setGamepadAnalog((short) 0, (short) 0, (short) 0, (short) 0);
        anyActive = false;
        jid = -1;
    }

    private void setButton(int retroId, boolean pressed) {
        if (retroId < 0 || retroId >= down.length || down[retroId] == pressed) return;
        down[retroId] = pressed;
        input.sendButton(retroId, pressed);
    }

    private int findGamepad() {
        if (jid >= 0 && GLFW.glfwJoystickPresent(jid) && GLFW.glfwJoystickIsGamepad(jid)) {
            return jid;
        }
        jid = -1;
        for (int j = GLFW.GLFW_JOYSTICK_1; j <= GLFW.GLFW_JOYSTICK_LAST; j++) {
            if (GLFW.glfwJoystickPresent(j) && GLFW.glfwJoystickIsGamepad(j)) {
                jid = j;
                LOGGER.info("[retroconsole] gamepad connected: jid={} name={}",
                        j, GLFW.glfwGetGamepadName(j));
                return j;
            }
        }
        return -1;
    }
}
