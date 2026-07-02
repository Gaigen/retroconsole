package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.retroconsole.bridge.LibretroBridge;
import com.retroconsole.network.RetroAnalogPacket;
import com.retroconsole.network.RetroInputPacket;
import com.retroconsole.network.RetroViewPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Fullscreen GUI screen for viewing and playing a retro console game.
 * Maps keyboard input to libretro button IDs and sends them to the server.
 *
 * Full DualShock 2 / Dreamcast mapping:
 *
 *   Face buttons:          Triggers & shoulders:
 *     Z = Cross  (A/8)       Q = L1 (L/10)
 *     X = Circle (B/0)       W = R1 (R/11)
 *     C = Square (X/9)       E = L2 (L2/12)  — analog trigger
 *     V = Triang (Y/1)       R = R2 (R2/13)  — analog trigger
 *
 *   System:                D-Pad:
 *     Enter = Start (3)      Arrows = Up/Down/Left/Right
 *     Shift = Select (2)
 *
 *   Left analog (IJKL):    Right analog (TFGH):
 *     I = Up       T = Up
 *     K = Down     G = Down
 *     J = Left     F = Left
 *     L = Right    H = Right
 *
 *   Stick clicks:
 *     N = L3 (14)    M = R3 (15)
 *
 *   Close: Esc
 */
public class TvScreen extends Screen {

    private final BlockPos consolePos;

    // Left analog stick tracking
    private boolean leftUp, leftDown, leftLeft, leftRight;
    // Right analog stick tracking
    private boolean rightUp, rightDown, rightLeft, rightRight;

    private static final short ANALOG_MAX = 32767;
    private static final short ANALOG_MIN = -32768;

    public TvScreen(BlockPos consolePos) {
        super(Component.literal("Retro Console"));
        this.consolePos = consolePos;
    }

    @Override
    protected void init() {
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, true));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        ClientConsoles.ScreenEntry entry = ClientConsoles.getScreen(consolePos);

        if (entry != null) {
            int texW = entry.width();
            int texH = entry.height();

            int screenW = this.width;
            int screenH = this.height - 30;

            float scaleX = (float) screenW / texW;
            float scaleY = (float) screenH / texH;
            float scale = Math.min(scaleX, scaleY);

            int drawW = (int) (texW * scale);
            int drawH = (int) (texH * scale);
            int drawX = (this.width - drawW) / 2;
            int drawY = (screenH - drawH) / 2;

            guiGraphics.blit(entry.id(), drawX, drawY, 0, 0, drawW, drawH, drawW, drawH);
        } else {
            String noSignal = "No Signal";
            int textWidth = this.font.width(noSignal);
            guiGraphics.drawString(this.font, noSignal,
                    (this.width - textWidth) / 2, this.height / 2, 0xFFFFFF);
        }

        String hints = "\u00a7eZ\u00a7r=A \u00a7eX\u00a7r=B \u00a7eC\u00a7r=X \u00a7eV\u00a7r=Y  "
                + "\u00a7eQ/W\u00a7r=L1/R1 \u00a7eE/R\u00a7r=L2/R2  "
                + "\u00a7eArrows\u00a7r=D-Pad  \u00a7eIJKL\u00a7r=L-Stick \u00a7eTFGH\u00a7r=R-Stick  "
                + "\u00a7eN/M\u00a7r=L3/R3  \u00a7eEnter\u00a7r=Start \u00a7eShift\u00a7r=Select  \u00a7eEsc\u00a7r=Close";
        int hintWidth = this.font.width(hints);
        guiGraphics.drawString(this.font, hints,
                (this.width - hintWidth) / 2, this.height - 20, 0xAAAAAA);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            close();
            return true;
        }

        int buttonId = mapKeyToButton(keyCode);
        if (buttonId >= 0) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, buttonId, true));
            return true;
        }

        if (handleAnalogKey(keyCode, true)) return true;

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        int buttonId = mapKeyToButton(keyCode);
        if (buttonId >= 0) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, buttonId, false));
            return true;
        }

        if (handleAnalogKey(keyCode, false)) return true;

        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /**
     * Map keyboard key to libretro joypad button ID.
     * Returns -1 if not a button key.
     */
    private static int mapKeyToButton(int keyCode) {
        return switch (keyCode) {
            // Face buttons
            case InputConstants.KEY_Z -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_A;      // Cross
            case InputConstants.KEY_X -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_B;      // Circle
            case InputConstants.KEY_C -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_X;      // Square
            case InputConstants.KEY_V -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_Y;      // Triangle
            // System
            case InputConstants.KEY_RETURN -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_START;
            case InputConstants.KEY_RSHIFT -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_SELECT;
            // D-Pad
            case InputConstants.KEY_UP -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_UP;
            case InputConstants.KEY_DOWN -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_DOWN;
            case InputConstants.KEY_LEFT -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_LEFT;
            case InputConstants.KEY_RIGHT -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_RIGHT;
            // Shoulders (L1/R1) — also triggers for Dreamcast
            case InputConstants.KEY_Q -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L;
            case InputConstants.KEY_W -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R;
            // Triggers (L2/R2) — analog triggers for PS2
            case InputConstants.KEY_E -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2;
            case InputConstants.KEY_R -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2;
            // Stick clicks
            case InputConstants.KEY_N -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L3;
            case InputConstants.KEY_M -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R3;
            default -> -1;
        };
    }

    /**
     * Handle analog stick keys. Returns true if the key was an analog key.
     */
    private boolean handleAnalogKey(int keyCode, boolean pressed) {
        boolean leftChanged = false;
        boolean rightChanged = false;

        // Left stick (IJKL)
        switch (keyCode) {
            case InputConstants.KEY_I -> { if (leftUp != pressed)    { leftUp = pressed;    leftChanged = true; } }
            case InputConstants.KEY_K -> { if (leftDown != pressed)  { leftDown = pressed;  leftChanged = true; } }
            case InputConstants.KEY_J -> { if (leftLeft != pressed)  { leftLeft = pressed;  leftChanged = true; } }
            case InputConstants.KEY_L -> { if (leftRight != pressed) { leftRight = pressed; leftChanged = true; } }
        }

        // Right stick (TFGH)
        switch (keyCode) {
            case InputConstants.KEY_T -> { if (rightUp != pressed)    { rightUp = pressed;    rightChanged = true; } }
            case InputConstants.KEY_G -> { if (rightDown != pressed)  { rightDown = pressed;  rightChanged = true; } }
            case InputConstants.KEY_F -> { if (rightLeft != pressed)  { rightLeft = pressed;  rightChanged = true; } }
            case InputConstants.KEY_H -> { if (rightRight != pressed) { rightRight = pressed; rightChanged = true; } }
        }

        if (leftChanged) {
            sendAnalogStick(0, leftLeft, leftRight, leftUp, leftDown);
        }
        if (rightChanged) {
            sendAnalogStick(1, rightLeft, rightRight, rightUp, rightDown);
        }

        return leftChanged || rightChanged;
    }

    /**
     * Send analog stick state for a given stick (0=left, 1=right).
     */
    private void sendAnalogStick(int stick, boolean left, boolean right, boolean up, boolean down) {
        short xVal = 0;
        if (right && !left) xVal = ANALOG_MAX;
        else if (left && !right) xVal = ANALOG_MIN;

        short yVal = 0;
        if (up && !down) yVal = ANALOG_MIN;    // up = negative Y
        else if (down && !up) yVal = ANALOG_MAX;

        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, stick, 0, xVal));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, stick, 1, yVal));
    }

    private void close() {
        sendReleaseAll();
        // Zero both analog sticks
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 0, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 1, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 1, 0, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 1, 1, (short) 0));
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, false));
        this.minecraft.setScreen(null);
    }

    private void sendReleaseAll() {
        int[] allButtons = {
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_A,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_B,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_X,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_Y,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_START,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_SELECT,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_UP,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_DOWN,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_LEFT,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_RIGHT,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L3,
                LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R3
        };
        for (int btn : allButtons) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, btn, false));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
