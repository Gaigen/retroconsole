package com.retroconsole.client.input;

import org.lwjgl.glfw.GLFW;

/**
 * Translates GLFW key codes (what {@code Screen.keyPressed} receives on desktop)
 * into libretro RETROK codes.
 *
 * <p>RETROK uses SDL 1.2 values: printable ASCII for letters/digits/symbols,
 * 27/13/9/8 for Escape/Return/Tab/Backspace, 273-281 for the navigation block,
 * 282+ for F1..F15 and 303-308 for the right/left modifiers.
 */
public final class KeyboardMapper {

    private KeyboardMapper() {}

    /** @return the RETROK code, or -1 when the key carries no state the cores poll. */
    public static int toRetroKey(int glfwKey) {
        if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            return 'a' + (glfwKey - GLFW.GLFW_KEY_A);
        }
        if (glfwKey >= GLFW.GLFW_KEY_0 && glfwKey <= GLFW.GLFW_KEY_9) {
            return '0' + (glfwKey - GLFW.GLFW_KEY_0);
        }
        if (glfwKey >= GLFW.GLFW_KEY_F1 && glfwKey <= GLFW.GLFW_KEY_F12) {
            return 282 + (glfwKey - GLFW.GLFW_KEY_F1);
        }
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_SPACE -> ' ';
            case GLFW.GLFW_KEY_APOSTROPHE -> '\'';
            case GLFW.GLFW_KEY_COMMA -> ',';
            case GLFW.GLFW_KEY_MINUS -> '-';
            case GLFW.GLFW_KEY_PERIOD -> '.';
            case GLFW.GLFW_KEY_SLASH -> '/';
            case GLFW.GLFW_KEY_SEMICOLON -> ';';
            case GLFW.GLFW_KEY_EQUAL -> '=';
            case GLFW.GLFW_KEY_LEFT_BRACKET -> '[';
            case GLFW.GLFW_KEY_BACKSLASH -> '\\';
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> ']';
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> 96;   // RETROK_BACKQUOTE (Doom console)
            case GLFW.GLFW_KEY_ESCAPE -> 27;         // RETROK_ESCAPE
            case GLFW.GLFW_KEY_ENTER -> 13;          // RETROK_RETURN
            case GLFW.GLFW_KEY_TAB -> 9;             // RETROK_TAB
            case GLFW.GLFW_KEY_BACKSPACE -> 8;       // RETROK_BACKSPACE
            case GLFW.GLFW_KEY_DELETE -> 127;        // RETROK_DELETE
            case GLFW.GLFW_KEY_UP -> 273;            // RETROK_UP
            case GLFW.GLFW_KEY_DOWN -> 274;          // RETROK_DOWN
            case GLFW.GLFW_KEY_RIGHT -> 275;         // RETROK_RIGHT
            case GLFW.GLFW_KEY_LEFT -> 276;          // RETROK_LEFT
            case GLFW.GLFW_KEY_INSERT -> 277;        // RETROK_INSERT
            case GLFW.GLFW_KEY_HOME -> 278;          // RETROK_HOME
            case GLFW.GLFW_KEY_END -> 279;           // RETROK_END
            case GLFW.GLFW_KEY_PAGE_UP -> 280;       // RETROK_PAGEUP
            case GLFW.GLFW_KEY_PAGE_DOWN -> 281;     // RETROK_PAGEDOWN
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 303;   // RETROK_RSHIFT
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 304;    // RETROK_LSHIFT
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 305; // RETROK_RCTRL
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 306;  // RETROK_LCTRL
            case GLFW.GLFW_KEY_RIGHT_ALT -> 307;     // RETROK_RALT
            case GLFW.GLFW_KEY_LEFT_ALT -> 308;      // RETROK_LALT
            default -> -1;
        };
    }
}
