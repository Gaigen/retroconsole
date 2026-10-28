package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Every key the console screens use.
 *
 * <p>Loader-neutral on purpose: the mappings are declared here and registered by
 * each loader module ({@code NeoForgeKeys} on NeoForge), the same way
 * {@code ClientPacketHandlers} keeps the payload table out of the shared code.
 */
public final class ModKeys {

    /** Everything below shows up under one tab in the vanilla key binds screen. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("retroconsole", "main"));

    // D-Pad
    public static final KeyMapping DPAD_UP = key("dpad_up", GLFW.GLFW_KEY_UP);
    public static final KeyMapping DPAD_DOWN = key("dpad_down", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping DPAD_LEFT = key("dpad_left", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping DPAD_RIGHT = key("dpad_right", GLFW.GLFW_KEY_RIGHT);

    // Face buttons (PS: Cross/Circle/Square/Triangle)
    public static final KeyMapping BTN_A = key("btn_a", GLFW.GLFW_KEY_Z);
    public static final KeyMapping BTN_B = key("btn_b", GLFW.GLFW_KEY_X);
    public static final KeyMapping BTN_X = key("btn_x", GLFW.GLFW_KEY_C);
    public static final KeyMapping BTN_Y = key("btn_y", GLFW.GLFW_KEY_V);

    // Shoulders L1/R1
    public static final KeyMapping BTN_L = key("btn_l", GLFW.GLFW_KEY_Q);
    public static final KeyMapping BTN_R = key("btn_r", GLFW.GLFW_KEY_W);

    // Triggers L2/R2
    public static final KeyMapping BTN_L2 = key("btn_l2", GLFW.GLFW_KEY_E);
    public static final KeyMapping BTN_R2 = key("btn_r2", GLFW.GLFW_KEY_R);

    // Stick clicks L3/R3
    public static final KeyMapping BTN_L3 = key("btn_l3", GLFW.GLFW_KEY_N);
    public static final KeyMapping BTN_R3 = key("btn_r3", GLFW.GLFW_KEY_M);

    // System
    public static final KeyMapping BTN_START = key("btn_start", GLFW.GLFW_KEY_ENTER);
    public static final KeyMapping BTN_SELECT = key("btn_select", GLFW.GLFW_KEY_RIGHT_SHIFT);

    // Left analog (IJKL)
    public static final KeyMapping ANALOG_LEFT_UP = key("analog_left_up", GLFW.GLFW_KEY_I);
    public static final KeyMapping ANALOG_LEFT_DOWN = key("analog_left_down", GLFW.GLFW_KEY_K);
    public static final KeyMapping ANALOG_LEFT_LEFT = key("analog_left_left", GLFW.GLFW_KEY_J);
    public static final KeyMapping ANALOG_LEFT_RIGHT = key("analog_left_right", GLFW.GLFW_KEY_L);

    // Right analog (TFGH)
    public static final KeyMapping ANALOG_RIGHT_UP = key("analog_right_up", GLFW.GLFW_KEY_T);
    public static final KeyMapping ANALOG_RIGHT_DOWN = key("analog_right_down", GLFW.GLFW_KEY_G);
    public static final KeyMapping ANALOG_RIGHT_LEFT = key("analog_right_left", GLFW.GLFW_KEY_F);
    public static final KeyMapping ANALOG_RIGHT_RIGHT = key("analog_right_right", GLFW.GLFW_KEY_H);

    public static final KeyMapping BTN_CURSOR_TOUCH = key("btn_cursor_touch", GLFW.GLFW_KEY_P);

    /** Toggle virtual stylus (right stick moves touch cursor). Default ; — not F2 (screenshot). */
    public static final KeyMapping VIRTUAL_STYLUS = key("virtual_stylus", GLFW.GLFW_KEY_SEMICOLON);

    public static final KeyMapping BTN_MIC = key("btn_mic", GLFW.GLFW_KEY_B);
    public static final KeyMapping BTN_LAYOUT = key("btn_layout", GLFW.GLFW_KEY_U);
    public static final KeyMapping BTN_LID = key("btn_lid", GLFW.GLFW_KEY_O);

    /** Everything a loader has to register, in one list. */
    public static final List<KeyMapping> ALL = List.of(
            DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
            BTN_A, BTN_B, BTN_X, BTN_Y,
            BTN_L, BTN_R, BTN_L2, BTN_R2, BTN_L3, BTN_R3,
            BTN_START, BTN_SELECT,
            ANALOG_LEFT_UP, ANALOG_LEFT_DOWN, ANALOG_LEFT_LEFT, ANALOG_LEFT_RIGHT,
            ANALOG_RIGHT_UP, ANALOG_RIGHT_DOWN, ANALOG_RIGHT_LEFT, ANALOG_RIGHT_RIGHT,
            BTN_MIC, BTN_LAYOUT, BTN_LID, BTN_CURSOR_TOUCH, VIRTUAL_STYLUS);

    /**
     * True when {@code event} is the key currently bound to {@code mapping}.
     *
     * <p>Stand-in for the loader extension method of the same name (NeoForge's
     * {@code IKeyMappingExtension}), which vanilla does not have. The mappings
     * above use the default conflict context and no modifier, so both of those
     * extra checks are always true and the binding comparison is all that is left.
     */
    public static boolean isActiveAndMatches(KeyMapping mapping, KeyEvent event) {
        return mapping.matches(event);
    }

    private static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping(
                "key.retroconsole." + name,
                InputConstants.Type.KEYSYM,
                glfwKey,
                CATEGORY
        );
    }

    private ModKeys() {}
}
