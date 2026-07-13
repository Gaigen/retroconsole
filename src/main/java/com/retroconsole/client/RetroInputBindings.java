package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.retroconsole.bridge.LibretroBridge;
import net.minecraft.client.KeyMapping;

/**
 * Maps {@link ModKeys} to libretro button / stick IDs.
 * Single source of truth for TvScreen input.
 */
public final class RetroInputBindings {

    public record ButtonBind(KeyMapping key, int retroId) {}

    public record StickBind(
            int stick,
            KeyMapping up,
            KeyMapping down,
            KeyMapping left,
            KeyMapping right
    ) {}

    public static final ButtonBind[] BUTTONS = {
            new ButtonBind(ModKeys.BTN_A, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_A),
            new ButtonBind(ModKeys.BTN_B, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_B),
            new ButtonBind(ModKeys.BTN_X, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_X),
            new ButtonBind(ModKeys.BTN_Y, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_Y),
            new ButtonBind(ModKeys.BTN_START, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_START),
            new ButtonBind(ModKeys.BTN_SELECT, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_SELECT),
            new ButtonBind(ModKeys.DPAD_UP, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_UP),
            new ButtonBind(ModKeys.DPAD_DOWN, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_DOWN),
            new ButtonBind(ModKeys.DPAD_LEFT, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_LEFT),
            new ButtonBind(ModKeys.DPAD_RIGHT, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_RIGHT),
            new ButtonBind(ModKeys.BTN_L, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L),
            new ButtonBind(ModKeys.BTN_R, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R),
            new ButtonBind(ModKeys.BTN_L2, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2),
            new ButtonBind(ModKeys.BTN_R2, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2),
            new ButtonBind(ModKeys.BTN_L3, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L3),
            new ButtonBind(ModKeys.BTN_R3, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R3),
    };

    public static final ButtonBind[] DS_BUTTONS = {
            new ButtonBind(ModKeys.BTN_MIC, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_MIC),
            new ButtonBind(ModKeys.BTN_LAYOUT, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_LAYOUT_NEXT),
            new ButtonBind(ModKeys.BTN_LID, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_LID),
            new ButtonBind(ModKeys.BTN_CURSOR_TOUCH, LibretroBridge.RETRO_DEVICE_ID_JOYPAD_CURSOR_TOUCH),
    };

    public static final StickBind LEFT_STICK = new StickBind(
            0,
            ModKeys.ANALOG_LEFT_UP,
            ModKeys.ANALOG_LEFT_DOWN,
            ModKeys.ANALOG_LEFT_LEFT,
            ModKeys.ANALOG_LEFT_RIGHT
    );

    public static final StickBind RIGHT_STICK = new StickBind(
            1,
            ModKeys.ANALOG_RIGHT_UP,
            ModKeys.ANALOG_RIGHT_DOWN,
            ModKeys.ANALOG_RIGHT_LEFT,
            ModKeys.ANALOG_RIGHT_RIGHT
    );

    private RetroInputBindings() {}

    public static int mapButton(InputConstants.Key key) {
        for (ButtonBind bind : BUTTONS) {
            if (bind.key().isActiveAndMatches(key)) {
                return bind.retroId();
            }
        }
        for (ButtonBind bind : DS_BUTTONS) {
            if (bind.key().isActiveAndMatches(key)) {
                return bind.retroId();
            }
        }
        return -1;
    }

    /**
     * @return true if the key belongs to this stick and stick state changed
     */
    public static boolean updateStick(StickBind bind, InputConstants.Key key, boolean pressed,
                                      StickState state) {
        boolean changed = false;
        if (bind.up().isActiveAndMatches(key) && state.up != pressed) {
            state.up = pressed;
            changed = true;
        }
        if (bind.down().isActiveAndMatches(key) && state.down != pressed) {
            state.down = pressed;
            changed = true;
        }
        if (bind.left().isActiveAndMatches(key) && state.left != pressed) {
            state.left = pressed;
            changed = true;
        }
        if (bind.right().isActiveAndMatches(key) && state.right != pressed) {
            state.right = pressed;
            changed = true;
        }
        return changed;
    }

    public static final class StickState {
        boolean up;
        boolean down;
        boolean left;
        boolean right;
    }
}
