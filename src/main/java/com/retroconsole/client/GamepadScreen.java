package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.retroconsole.client.input.GamepadPoller;
import com.retroconsole.client.input.RetroInputSender;
import com.retroconsole.config.ModConfig;
import com.retroconsole.item.GamepadItem;
import com.retroconsole.network.RetroSaveStatePacket;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal invisible screen that captures keyboard input for in-world TV play.
 * The world stays visible; Minecraft movement is suppressed while this screen is open.
 */
public class GamepadScreen extends net.minecraft.client.gui.screens.Screen {

    private static final int COL_PANEL = 0xCC14161C;
    private static final int COL_EDGE = 0xFF2A2F3A;

    private final BlockPos consolePos;
    private final RetroInputSender input;
    private final GamepadPoller gamepad;
    private boolean closed;
    private boolean showHints;

    public GamepadScreen(BlockPos consolePos) {
        super(ModTexts.c("gamepad.title"));
        this.consolePos = consolePos;
        this.input = new RetroInputSender(consolePos);
        this.gamepad = new GamepadPoller(this.input);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (showHints) {
            renderHintsOverlay(guiGraphics);
        } else {
            renderHud(guiGraphics);
        }
    }

    private void renderHud(GuiGraphics g) {
        int barH = 20;
        int y = this.height - barH;
        g.fill(0, y, this.width, this.height, COL_PANEL);
        g.fill(0, y, this.width, y + 1, COL_EDGE);
        g.drawCenteredString(this.font, ModTexts.s("gamepad.hud"), this.width / 2, y + 6, 0xE0E0E0);
    }

    private void renderHintsOverlay(GuiGraphics g) {
        List<String> left = new ArrayList<>();
        left.add(ModTexts.s("tv.hints.buttons"));
        left.add(hintLine(ModKeys.BTN_A, "A"));
        left.add(hintLine(ModKeys.BTN_B, "B"));
        left.add(hintLine(ModKeys.BTN_X, "X"));
        left.add(hintLine(ModKeys.BTN_Y, "Y"));
        left.add(hintLine(ModKeys.BTN_L, "L1"));
        left.add(hintLine(ModKeys.BTN_R, "R1"));
        left.add(hintLine(ModKeys.BTN_L2, "L2"));
        left.add(hintLine(ModKeys.BTN_R2, "R2"));

        List<String> right = new ArrayList<>();
        right.add(ModTexts.s("tv.hints.directions"));
        right.add(stickLine(com.retroconsole.client.RetroInputBindings.LEFT_STICK, "L-Stick"));
        right.add(stickLine(com.retroconsole.client.RetroInputBindings.RIGHT_STICK, "R-Stick"));
        right.add("");
        right.add(ModTexts.s("tv.hints.system"));
        right.add(hintLine(ModKeys.BTN_START, "Start"));
        right.add(hintLine(ModKeys.BTN_SELECT, "Select"));
        right.add(ModTexts.s("tv.hints.save"));
        right.add(ModTexts.s("tv.hints.load"));
        right.add(ModTexts.s("tv.hints.exit"));

        int pad = 14;
        int gap = 20;
        int colW1 = left.stream().mapToInt(font::width).max().orElse(0);
        int colW2 = right.stream().mapToInt(font::width).max().orElse(0);
        int rows = Math.max(left.size(), right.size());
        int cardW = pad * 2 + colW1 + gap + colW2;
        int cardH = pad * 2 + 14 + rows * 12 + 14;
        int x = (this.width - cardW) / 2;
        int y = (this.height - cardH) / 2;

        g.fill(0, 0, this.width, this.height, 0x80000000);
        g.fill(x - 1, y - 1, x + cardW + 1, y + cardH + 1, 0xFF3C4250);
        g.fill(x, y, x + cardW, y + cardH, 0xFF14161C);
        g.drawString(font, ModTexts.s("tv.hints.title"), x + pad, y + pad, 0xFFFFFF);
        int ty = y + pad + 14;
        for (int i = 0; i < left.size(); i++) {
            g.drawString(font, left.get(i), x + pad, ty + i * 12, 0xE0E0E0);
        }
        for (int i = 0; i < right.size(); i++) {
            g.drawString(font, right.get(i), x + pad + colW1 + gap, ty + i * 12, 0xE0E0E0);
        }
        g.drawString(font, ModTexts.s("gamepad.hints.footer"), x + pad, y + cardH - 12 - 2, 0xFFFFFF);
    }

    private static String hintLine(KeyMapping key, String action) {
        return ModTexts.s("tv.hint.bind", key.getTranslatedKeyMessage().getString(), action);
    }

    private static String stickLine(com.retroconsole.client.RetroInputBindings.StickBind stick, String label) {
        return ModTexts.s("tv.hint.sticks",
                stick.up().getTranslatedKeyMessage().getString(),
                stick.down().getTranslatedKeyMessage().getString(),
                stick.left().getTranslatedKeyMessage().getString(),
                stick.right().getTranslatedKeyMessage().getString(),
                label);
    }

    @Override
    public void tick() {
        super.tick();
        if (closed) return;
        var player = minecraft.player;
        if (player == null) {
            onClose();
            return;
        }

        boolean stillHolding = GamepadItem.isLinkedTo(player.getMainHandItem(), consolePos)
                || GamepadItem.isLinkedTo(player.getOffhandItem(), consolePos);
        if (!stillHolding) {
            player.displayClientMessage(Component.translatable("retroconsole.gamepad.lost"), true);
            onClose();
            return;
        }

        int controlDist = ModConfig.controlDistance();
        double distSq = player.distanceToSqr(
                consolePos.getX() + 0.5, consolePos.getY() + 0.5, consolePos.getZ() + 0.5);
        if (distSq > (double) controlDist * controlDist) {
            player.displayClientMessage(Component.translatable("retroconsole.gamepad.too_far"), true);
            onClose();
            return;
        }

        gamepad.poll();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (showHints) {
                showHints = false;
                return true;
            }
            onClose();
            return true;
        }
        if (keyCode == InputConstants.KEY_F1) {
            showHints = !showHints;
            return true;
        }
        if (keyCode == InputConstants.KEY_F5) {
            PacketDistributor.sendToServer(new RetroSaveStatePacket(consolePos, 0, true, false));
            return true;
        }
        if (keyCode == InputConstants.KEY_F6) {
            PacketDistributor.sendToServer(new RetroSaveStatePacket(consolePos, 0, false, false));
            return true;
        }

        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        if (input.handleKeyPressed(key)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        if (input.handleKeyReleased(key)) return true;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (closed) return;
        closed = true;
        PacketDistributor.sendToServer(new RetroSaveStatePacket(consolePos, 0, true, true));
        input.releaseAll();
        input.sendAnalogZeros();
        gamepad.reset();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible.
    }
}
