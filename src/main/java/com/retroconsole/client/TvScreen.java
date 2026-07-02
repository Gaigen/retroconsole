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
 * Button mapping (Dreamcast):
 *   Z       -> A        X       -> B
 *   C       -> X        V       -> Y
 *   Enter   -> Start    RShift  -> Select
 *   Arrows  -> D-Pad    Q/W     -> L/R
 *
 * Analog stick (left):
 *   I/J/K/L -> Up/Left/Down/Right (digital, max deflection)
 */
public class TvScreen extends Screen {

    private final BlockPos consolePos;

    // Track analog keys for combined axis values
    private boolean analogUp = false, analogDown = false;
    private boolean analogLeft = false, analogRight = false;
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

        // Hint text
        String hints = "\u00a7eZ\u00a7r=A \u00a7eX\u00a7r=B \u00a7eC\u00a7r=X \u00a7eV\u00a7r=Y  "
                + "\u00a7eEnter\u00a7r=Start \u00a7eShift\u00a7r=Select  "
                + "\u00a7eArrows\u00a7r=D-Pad \u00a7eIJKL\u00a7r=Analog  "
                + "\u00a7eQ/W\u00a7r=L/R \u00a7eEsc\u00a7r=Close";
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

        // Check button mapping
        int buttonId = mapKeyToButton(keyCode);
        if (buttonId >= 0) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, buttonId, true));
            return true;
        }

        // Check analog stick keys
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
     * Map a GLFW key code to a libretro button ID.
     * Returns -1 if the key is not mapped.
     */
    private static int mapKeyToButton(int keyCode) {
        return switch (keyCode) {
            case InputConstants.KEY_Z -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_A;
            case InputConstants.KEY_X -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_B;
            case InputConstants.KEY_C -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_X;
            case InputConstants.KEY_V -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_Y;
            case InputConstants.KEY_RETURN -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_START;
            case InputConstants.KEY_RSHIFT -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_SELECT;
            case InputConstants.KEY_UP -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_UP;
            case InputConstants.KEY_DOWN -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_DOWN;
            case InputConstants.KEY_LEFT -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_LEFT;
            case InputConstants.KEY_RIGHT -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_RIGHT;
            case InputConstants.KEY_Q -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L;
            case InputConstants.KEY_W -> LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R;
            default -> -1;
        };
    }

    /**
     * Handle analog stick keys (IJKL). Returns true if the key was an analog key.
     * Sends analog state as digital max deflection (32767 / -32768).
     */
    private boolean handleAnalogKey(int keyCode, boolean pressed) {
        boolean changed = false;
        switch (keyCode) {
            case InputConstants.KEY_I -> { if (analogUp != pressed) { analogUp = pressed; changed = true; } }
            case InputConstants.KEY_K -> { if (analogDown != pressed) { analogDown = pressed; changed = true; } }
            case InputConstants.KEY_J -> { if (analogLeft != pressed) { analogLeft = pressed; changed = true; } }
            case InputConstants.KEY_L -> { if (analogRight != pressed) { analogRight = pressed; changed = true; } }
            default -> { return false; }
        }

        if (changed) {
            // Compute combined axis: opposing keys cancel out
            short xVal = 0;
            if (analogRight && !analogLeft) xVal = ANALOG_MAX;
            else if (analogLeft && !analogRight) xVal = ANALOG_MIN;

            short yVal = 0;
            if (analogUp && !analogDown) yVal = ANALOG_MIN;  // up = negative Y in libretro
            else if (analogDown && !analogUp) yVal = ANALOG_MAX;

            // Left stick: stick=0, axis 0=X, 1=Y
            PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 0, xVal));
            PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 1, yVal));
        }
        return true;
    }

    private void close() {
        sendReleaseAll();
        // Zero analog sticks
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 0, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 1, (short) 0));
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
