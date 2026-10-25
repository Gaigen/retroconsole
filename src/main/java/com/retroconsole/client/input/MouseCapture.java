package com.retroconsole.client.input;

import com.retroconsole.network.RetroMousePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Relative mouse capture for keyboard+mouse cores (Doom 3 and friends).
 *
 * <p>The cursor is released to {@code GLFW_CURSOR_DISABLED} so the OS reports
 * unbounded movement instead of a screen position; each frame the delta since
 * the previous read is sent to the server, which feeds it to the core as
 * {@code RETRO_DEVICE_MOUSE_X/Y}. Releasing the capture restores the normal
 * cursor and parks it in the middle of the window.
 */
public final class MouseCapture {

    private final BlockPos consolePos;

    private boolean active;
    private double lastX;
    private double lastY;
    private double accX;
    private double accY;
    private int wheel;
    private int buttons;
    private int sentButtons;

    public MouseCapture(BlockPos consolePos) {
        this.consolePos = consolePos;
    }

    public boolean isActive() {
        return active;
    }

    public void grab(Minecraft mc) {
        if (active) return;
        long handle = mc.getWindow().getWindow();
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(handle, x, y);
        lastX = x[0];
        lastY = y[0];
        accX = accY = 0;
        wheel = 0;
        buttons = 0;
        sentButtons = 0;
        active = true;
        GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    public void release(Minecraft mc) {
        if (!active) return;
        // Make sure the game does not keep a stale button pressed.
        if (buttons != 0 || sentButtons != 0 || wheel != 0) {
            PacketDistributor.sendToServer(new RetroMousePacket(consolePos, 0, 0, 0, 0));
        }
        buttons = 0;
        sentButtons = 0;
        wheel = 0;
        accX = accY = 0;
        active = false;
        long handle = mc.getWindow().getWindow();
        GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        GLFW.glfwSetCursorPos(handle,
                mc.getWindow().getScreenWidth() / 2.0,
                mc.getWindow().getScreenHeight() / 2.0);
    }

    /** Called once per frame while captured; emits a packet when something changed. */
    public void poll(Minecraft mc) {
        if (!active) return;
        long handle = mc.getWindow().getWindow();
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(handle, x, y);
        accX += x[0] - lastX;
        accY += y[0] - lastY;
        lastX = x[0];
        lastY = y[0];

        int dx = (int) accX;
        int dy = (int) accY;
        accX -= dx;
        accY -= dy;
        if (dx == 0 && dy == 0 && wheel == 0 && buttons == sentButtons) return;

        int detents = wheel;
        wheel = 0;
        sentButtons = buttons;
        PacketDistributor.sendToServer(new RetroMousePacket(consolePos, dx, dy, buttons, detents));
    }

    /** GLFW/Minecraft button index (0 left, 1 right, 2 middle, 3/4 extra) -> state. */
    public void setButton(int button, boolean down) {
        if (button < 0 || button >= 5) return;
        if (down) buttons |= 1 << button;
        else buttons &= ~(1 << button);
    }

    public void addWheel(int detents) {
        wheel += detents;
    }
}
