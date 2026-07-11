package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.retroconsole.RetroConsole;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public final class ModKeys {

    private static final String CATEGORY = "key.categories.retroconsole";

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

    private static KeyMapping key(String name, int glfwKey) {
        return new KeyMapping(
                "key.retroconsole." + name,
                InputConstants.Type.KEYSYM,
                glfwKey,
                CATEGORY
        );
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(DPAD_UP);
        event.register(DPAD_DOWN);
        event.register(DPAD_LEFT);
        event.register(DPAD_RIGHT);
        event.register(BTN_A);
        event.register(BTN_B);
        event.register(BTN_X);
        event.register(BTN_Y);
        event.register(BTN_L);
        event.register(BTN_R);
        event.register(BTN_L2);
        event.register(BTN_R2);
        event.register(BTN_L3);
        event.register(BTN_R3);
        event.register(BTN_START);
        event.register(BTN_SELECT);
        event.register(ANALOG_LEFT_UP);
        event.register(ANALOG_LEFT_DOWN);
        event.register(ANALOG_LEFT_LEFT);
        event.register(ANALOG_LEFT_RIGHT);
        event.register(ANALOG_RIGHT_UP);
        event.register(ANALOG_RIGHT_DOWN);
        event.register(ANALOG_RIGHT_LEFT);
        event.register(ANALOG_RIGHT_RIGHT);
    }

    private ModKeys() {}
}
