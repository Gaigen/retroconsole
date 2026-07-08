package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.retroconsole.client.library.PlayStats;
import com.retroconsole.client.library.SaveStates;
import com.retroconsole.network.RetroAnalogPacket;
import net.minecraft.util.FastColor;
import com.retroconsole.network.RetroInputPacket;
import com.retroconsole.network.RetroSaveStatePacket;
import com.retroconsole.network.RetroViewPacket;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;

/**
 * Fullscreen GUI for viewing and playing a retro console game.
 * Input bindings live in {@link ModKeys} and are resolved via {@link RetroInputBindings}.
 */
public class TvScreen extends Screen {

    private final BlockPos consolePos;
    private final String romId;
    private long lastFlush = Util.getMillis();
    private boolean closed;
    private int[] thumbPixels;
    private int thumbW;
    private int thumbH;

    private final RetroInputBindings.StickState leftStick = new RetroInputBindings.StickState();
    private final RetroInputBindings.StickState rightStick = new RetroInputBindings.StickState();

    private static final short ANALOG_MAX = 32767;
    private static final short ANALOG_MIN = -32768;

    public TvScreen(BlockPos consolePos, String romId) {
        super(Component.literal("Retro Console"));
        this.consolePos = consolePos;
        this.romId = romId != null ? romId : "";
    }

    @Override
    protected void init() {
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, true));
    }

    @Override
    public void tick() {
        super.tick();
        if (romId.isEmpty()) return;
        long now = Util.getMillis();
        if (now - lastFlush >= 60_000) {
            PlayStats.addPlaytime(romId, (now - lastFlush) / 1000);
            lastFlush = now;
        }
    }

    @Override
    public void removed() {
        if (!romId.isEmpty()) {
            PlayStats.addPlaytime(romId, (Util.getMillis() - lastFlush) / 1000);
            lastFlush = Util.getMillis();
            saveThumbnail();
        }
        super.removed();
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
            snapshotThumb(entry);
        } else {
            String noSignal = "No Signal";
            int textWidth = this.font.width(noSignal);
            guiGraphics.drawString(this.font, noSignal,
                    (this.width - textWidth) / 2, this.height / 2, 0xFFFFFF);
        }

        String hints = hint(ModKeys.BTN_A, "A") + " "
                + hint(ModKeys.BTN_B, "B") + " "
                + hint(ModKeys.BTN_X, "X") + " "
                + hint(ModKeys.BTN_Y, "Y") + "  "
                + hint(ModKeys.BTN_L, "L1") + "/"
                + hint(ModKeys.BTN_R, "R1") + " "
                + hint(ModKeys.BTN_L2, "L2") + "/"
                + hint(ModKeys.BTN_R2, "R2") + "  "
                + keysHint(ModKeys.DPAD_UP, ModKeys.DPAD_DOWN, ModKeys.DPAD_LEFT, ModKeys.DPAD_RIGHT, "D-Pad") + " "
                + stickHint(RetroInputBindings.LEFT_STICK, "L-Stick") + " "
                + stickHint(RetroInputBindings.RIGHT_STICK, "R-Stick") + "  "
                + hint(ModKeys.BTN_L3, "L3") + "/"
                + hint(ModKeys.BTN_R3, "R3") + "  "
                + hint(ModKeys.BTN_START, "Start") + " "
                + hint(ModKeys.BTN_SELECT, "Select") + "  "
                + "\u00a7eF5/F6\u00a7r=Save/Load  \u00a7eEsc\u00a7r=Close";
        int hintWidth = this.font.width(hints);
        guiGraphics.drawString(this.font, hints,
                (this.width - hintWidth) / 2, this.height - 20, 0xAAAAAA);
    }

    private static String hint(KeyMapping key, String action) {
        return "\u00a7e" + key.getTranslatedKeyMessage().getString() + "\u00a7r=" + action;
    }

    private static String stickHint(RetroInputBindings.StickBind stick, String label) {
        return keysHint(stick.up(), stick.down(), stick.left(), stick.right(), label);
    }

    private static String keysHint(KeyMapping up, KeyMapping down, KeyMapping left, KeyMapping right,
                                   String label) {
        return "\u00a7e"
                + up.getTranslatedKeyMessage().getString()
                + down.getTranslatedKeyMessage().getString()
                + left.getTranslatedKeyMessage().getString()
                + right.getTranslatedKeyMessage().getString()
                + "\u00a7r=" + label;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            this.minecraft.setScreen(null);
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

        int buttonId = RetroInputBindings.mapButton(key);
        if (buttonId >= 0) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, buttonId, true));
            return true;
        }

        if (handleAnalogKey(key, true)) return true;

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);

        int buttonId = RetroInputBindings.mapButton(key);
        if (buttonId >= 0) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, buttonId, false));
            return true;
        }

        if (handleAnalogKey(key, false)) return true;

        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    private boolean handleAnalogKey(InputConstants.Key key, boolean pressed) {
        boolean leftChanged = RetroInputBindings.updateStick(
                RetroInputBindings.LEFT_STICK, key, pressed, leftStick);
        boolean rightChanged = RetroInputBindings.updateStick(
                RetroInputBindings.RIGHT_STICK, key, pressed, rightStick);

        if (leftChanged) {
            sendAnalogStick(0, leftStick.left, leftStick.right, leftStick.up, leftStick.down);
        }
        if (rightChanged) {
            sendAnalogStick(1, rightStick.left, rightStick.right, rightStick.up, rightStick.down);
        }

        return leftChanged || rightChanged;
    }

    private void sendAnalogStick(int stick, boolean left, boolean right, boolean up, boolean down) {
        short xVal = 0;
        if (right && !left) xVal = ANALOG_MAX;
        else if (left && !right) xVal = ANALOG_MIN;

        short yVal = 0;
        if (up && !down) yVal = ANALOG_MIN;
        else if (down && !up) yVal = ANALOG_MAX;

        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, stick, 0, xVal));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, stick, 1, yVal));
    }

    @Override
    public void onClose() {
        if (closed) return;
        closed = true;

        if (!romId.isEmpty()) {
            PacketDistributor.sendToServer(new RetroSaveStatePacket(consolePos, 0, true, true));
        }

        sendReleaseAll();
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 0, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 1, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 1, 0, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 1, 1, (short) 0));
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, false));
        super.onClose();
    }

    private void snapshotThumb(ClientConsoles.ScreenEntry entry) {
        int[] abgr = entry.lastAbgr();
        if (abgr == null) return;
        int w = entry.width();
        int h = entry.height();
        if (w <= 0 || h <= 0 || abgr.length < w * h) return;
        thumbW = w;
        thumbH = h;
        thumbPixels = abgr.clone();
    }

    private void saveThumbnail() {
        if (thumbPixels == null || thumbW <= 0 || thumbH <= 0) return;
        try (NativeImage img = new NativeImage(thumbW, thumbH, false)) {
            for (int y = 0; y < thumbH; y++) {
                for (int x = 0; x < thumbW; x++) {
                    int p = thumbPixels[y * thumbW + x];
                    int r = p & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = (p >> 16) & 0xFF;
                    img.setPixelRGBA(x, y, FastColor.ABGR32.color(255, b, g, r));
                }
            }
            var thumb = SaveStates.thumbFor(romId);
            Files.createDirectories(thumb.getParent());
            img.writeToFile(thumb.toFile());
            TextureCache.invalidate(thumb);
        } catch (Exception ignored) {}
    }

    private void sendReleaseAll() {
        for (RetroInputBindings.ButtonBind bind : RetroInputBindings.BUTTONS) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, bind.retroId(), false));
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
