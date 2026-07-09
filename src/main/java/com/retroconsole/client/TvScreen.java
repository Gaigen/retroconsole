package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.retroconsole.client.library.PlayStats;
import com.retroconsole.client.library.SaveStates;
import com.retroconsole.client.library.SoundPrefs;
import com.retroconsole.network.RetroAnalogPacket;
import com.retroconsole.network.RetroInputPacket;
import com.retroconsole.network.RetroPowerOffPacket;
import com.retroconsole.network.RetroSaveStatePacket;
import com.retroconsole.network.RetroViewPacket;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Fullscreen GUI for viewing and playing a retro console game.
 * Input bindings live in {@link ModKeys} and are resolved via {@link RetroInputBindings}.
 *
 * <p>Bottom bar: power-off (stops console and returns to CoreSelectScreen), help overlay
 * toggle, game title centered, volume slider on the right.
 */
public class TvScreen extends Screen {

    /** Bottom bar height; single source of truth for layout. */
    private static final int BAR_HEIGHT = 24;

    // Colors matching CoreSelectScreen for a consistent UI look.
    private static final int COL_PANEL_2 = 0xFF14161C;
    private static final int COL_EDGE = 0xFF2A2F3A;

    private static final short ANALOG_MAX = 32767;
    private static final short ANALOG_MIN = -32768;

    private final BlockPos consolePos;
    private final String romId;
    private final String displayName;

    private long lastFlush = Util.getMillis();
    private boolean closed;
    private boolean showHints;

    private int[] thumbPixels;
    private int thumbW;
    private int thumbH;

    private final RetroInputBindings.StickState leftStick = new RetroInputBindings.StickState();
    private final RetroInputBindings.StickState rightStick = new RetroInputBindings.StickState();

    private short sentLx, sentLy, sentRx, sentRy;

    public TvScreen(BlockPos consolePos, String romId) {
        super(ModTexts.c("gui.title"));
        this.consolePos = consolePos;
        this.romId = romId != null ? romId : "";
        this.displayName = prettyName(this.romId);
    }

    /** {@code nes/Super_Game.nes} → {@code Super Game}. romId is the only name source for now. */
    private static String prettyName(String romId) {
        if (romId == null || romId.isEmpty()) return "";
        String name = romId.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ');
    }

    @Override
    protected void init() {
        // init() also runs on window resize: widgets are recreated while state
        // (showHints) lives in fields. Server addViewer is a Set, so a repeat
        // RetroViewPacket(true) is harmless.
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, true));

        int y = this.height - BAR_HEIGHT + 3;
        int h = BAR_HEIGHT - 6;

        int barLeft = 4;
        barLeft = addLeft(Button.builder(ModTexts.c("tv.power_off"), b -> {
            blip(0.8f);
            powerOffToMenu();
        }), barLeft, y, 48, h);
        barLeft = addLeft(Button.builder(ModTexts.c("tv.controls"), b -> {
            blip(1.2f);
            showHints = !showHints;
        }), barLeft, y, 88, h);
        // Add new left-side buttons here: barLeft = addLeft(...)

        int barRight = this.width - 4;
        barRight = addRight(new VolumeSlider(0, y, 110, h, SoundPrefs.volume()), barRight);
        // Add new right-side widgets here: barRight = addRight(...)
    }

    private int addLeft(Button.Builder builder, int x, int y, int w, int h) {
        addRenderableWidget(builder.bounds(x, y, w, h).build());
        return x + w + 4;
    }

    private int addRight(AbstractSliderButton widget, int rightEdge) {
        widget.setX(rightEdge - widget.getWidth());
        addRenderableWidget(widget);
        return widget.getX() - 4;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Order matters: background → TV frame → bar → widgets → help overlay.
        // super.render() is not used: it redraws the dimming background over the
        // frame (same approach as CoreSelectScreen).
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        renderTvFrame(guiGraphics);
        renderBar(guiGraphics);
        for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (showHints) renderHintsOverlay(guiGraphics);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // OPTIMIZATION: vanilla renderBackground draws the world + blur every frame.
        // That is hidden behind the TV frame anyway — use a plain black fill.
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF000000);
    }

    private void renderTvFrame(GuiGraphics guiGraphics) {
        ClientConsoles.ScreenEntry entry = ClientConsoles.getScreen(consolePos);
        int screenH = this.height - BAR_HEIGHT;

        if (entry != null) {
            int texW = entry.width();
            int texH = entry.height();

            float scale = Math.min((float) this.width / texW, (float) screenH / texH);
            int drawW = (int) (texW * scale);
            int drawH = (int) (texH * scale);
            int drawX = (this.width - drawW) / 2;
            int drawY = (screenH - drawH) / 2;

            guiGraphics.blit(entry.id(), drawX, drawY, 0, 0, drawW, drawH, drawW, drawH);
            // OPTIMIZATION: snapshotThumb is no longer called here — it used to be
            // abgr.clone() (~11 MB) on EVERY render(). Thumbnail is captured once in removed().
        } else {
            String noSignal = ModTexts.s("gui.no_signal");
            guiGraphics.drawCenteredString(this.font, noSignal, this.width / 2, screenH / 2, 0xFFFFFF);
        }
    }

    private void renderBar(GuiGraphics guiGraphics) {
        guiGraphics.fill(0, this.height - BAR_HEIGHT, this.width, this.height, COL_PANEL_2);
        guiGraphics.fill(0, this.height - BAR_HEIGHT, this.width, this.height - BAR_HEIGHT + 1, COL_EDGE);
        if (!displayName.isEmpty()) {
            // Leave room for left buttons (144px) and right slider (114px).
            int avail = Math.max(40, this.width - 2 * 160);
            String title = this.font.plainSubstrByWidth(displayName, avail);
            guiGraphics.drawCenteredString(this.font, title,
                    this.width / 2, this.height - BAR_HEIGHT / 2 - 4, 0xC8C8C8);
        }
    }

    private void renderHintsOverlay(GuiGraphics g) {
        List<String> left = new ArrayList<>();
        left.add(ModTexts.s("tv.hints.buttons"));
        left.add(line(ModKeys.BTN_A, "A"));
        left.add(line(ModKeys.BTN_B, "B"));
        left.add(line(ModKeys.BTN_X, "X"));
        left.add(line(ModKeys.BTN_Y, "Y"));
        left.add(line(ModKeys.BTN_L, "L1"));
        left.add(line(ModKeys.BTN_R, "R1"));
        left.add(line(ModKeys.BTN_L2, "L2"));
        left.add(line(ModKeys.BTN_R2, "R2"));
        left.add(line(ModKeys.BTN_L3, "L3"));
        left.add(line(ModKeys.BTN_R3, "R3"));

        List<String> right = new ArrayList<>();
        right.add(ModTexts.s("tv.hints.directions"));
        right.add(keysLine(ModKeys.DPAD_UP, ModKeys.DPAD_DOWN, ModKeys.DPAD_LEFT, ModKeys.DPAD_RIGHT, "D-Pad"));
        right.add(stickLine(RetroInputBindings.LEFT_STICK, "L-Stick"));
        right.add(stickLine(RetroInputBindings.RIGHT_STICK, "R-Stick"));
        right.add("");
        right.add(ModTexts.s("tv.hints.system"));
        right.add(line(ModKeys.BTN_START, "Start"));
        right.add(line(ModKeys.BTN_SELECT, "Select"));
        right.add(ModTexts.s("tv.hints.save"));
        right.add(ModTexts.s("tv.hints.load"));
        right.add(ModTexts.s("tv.hints.help"));
        right.add(ModTexts.s("tv.hints.exit"));

        int colW1 = 0;
        int colW2 = 0;
        for (String s : left) colW1 = Math.max(colW1, font.width(s));
        for (String s : right) colW2 = Math.max(colW2, font.width(s));

        int pad = 14;
        int gap = 20;
        int rows = Math.max(left.size(), right.size());
        int cardW = pad * 2 + colW1 + gap + colW2;
        int cardH = pad * 2 + 14 + rows * 12 + 14;
        int x = (this.width - cardW) / 2;
        int y = (this.height - BAR_HEIGHT - cardH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, this.width, this.height, 0xB0000000);
        g.fill(x - 1, y - 1, x + cardW + 1, y + cardH + 1, 0xFF3C4250);
        g.fill(x, y, x + cardW, y + cardH, COL_PANEL_2);

        g.drawString(font, ModTexts.s("tv.hints.title"), x + pad, y + pad, 0xFFFFFF);
        int ty = y + pad + 14;
        for (int i = 0; i < left.size(); i++) {
            g.drawString(font, left.get(i), x + pad, ty + i * 12, 0xE0E0E0);
        }
        for (int i = 0; i < right.size(); i++) {
            g.drawString(font, right.get(i), x + pad + colW1 + gap, ty + i * 12, 0xE0E0E0);
        }
        g.drawString(font, ModTexts.s("tv.hints.footer"),
                x + pad, y + cardH - 12 - 2, 0xFFFFFF);
        g.pose().popPose();
    }

    private static String line(KeyMapping key, String action) {
        return ModTexts.s("tv.hint.bind", key.getTranslatedKeyMessage().getString(), action);
    }

    private static String stickLine(RetroInputBindings.StickBind stick, String label) {
        return keysLine(stick.up(), stick.down(), stick.left(), stick.right(), label);
    }

    private static String keysLine(KeyMapping up, KeyMapping down, KeyMapping left, KeyMapping right,
                                   String label) {
        return ModTexts.s("tv.hint.sticks",
                up.getTranslatedKeyMessage().getString(),
                down.getTranslatedKeyMessage().getString(),
                left.getTranslatedKeyMessage().getString(),
                right.getTranslatedKeyMessage().getString(),
                label);
    }

    private static class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y, int w, int h, double initial) {
            super(x, y, w, h, Component.empty(), Mth.clamp(initial, 0.0, 1.0));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(ModTexts.c("tv.volume", (int) Math.round(value * 100)));
        }

        @Override
        protected void applyValue() {
            // Apply live while dragging; do not persist to disk yet.
            ClientAudioHandler.setVolume((float) value);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            SoundPrefs.setVolume((float) value); // persist once on release
        }
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
            // Thumbnail is captured ONCE here, not every frame.
            // removed() runs synchronously inside setScreen — before the stop packet
            // arrives and dispose() removes ScreenEntry, so peekScreen still sees the last frame.
            ClientConsoles.ScreenEntry entry = ClientConsoles.peekScreen(consolePos);
            if (entry != null) snapshotThumb(entry);
            saveThumbnail();
        }
        super.removed();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (showHints) {
                showHints = false;
                return true;
            }
            // BUGFIX: minecraft.setScreen(null) used to skip onClose(), losing
            // autosave, release-all, and RetroViewPacket(false).
            this.onClose();
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // bar buttons work even with help open
        if (showHints) {
            showHints = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        // BUGFIX: after a click the widget stays focused and Enter/Space would
        // activate the button instead of Start/game input. Clear focus on release
        // (not in mouseClicked — that would break slider dragging).
        setFocused(null);
        return handled;
    }

    private boolean handleAnalogKey(InputConstants.Key key, boolean pressed) {
        boolean leftChanged = RetroInputBindings.updateStick(
                RetroInputBindings.LEFT_STICK, key, pressed, leftStick);
        boolean rightChanged = RetroInputBindings.updateStick(
                RetroInputBindings.RIGHT_STICK, key, pressed, rightStick);
        if (leftChanged || rightChanged) {
            sendAnalogIfChanged();
        }
        return leftChanged || rightChanged;
    }

    private short stickToX(RetroInputBindings.StickState stick) {
        if (stick.right && !stick.left) return ANALOG_MAX;
        if (stick.left && !stick.right) return ANALOG_MIN;
        return 0;
    }

    private short stickToY(RetroInputBindings.StickState stick) {
        if (stick.up && !stick.down) return ANALOG_MIN;
        if (stick.down && !stick.up) return ANALOG_MAX;
        return 0;
    }

    /** One packet with all axes — only when stick state changes. */
    private void sendAnalogIfChanged() {
        short lx = stickToX(leftStick);
        short ly = stickToY(leftStick);
        short rx = stickToX(rightStick);
        short ry = stickToY(rightStick);
        if (lx == sentLx && ly == sentLy && rx == sentRx && ry == sentRy) return;
        sentLx = lx;
        sentLy = ly;
        sentRx = rx;
        sentRy = ry;
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, lx, ly, rx, ry));
    }

    /**
     * Power off: stop the server console and return to the game picker.
     * Autosave is handled server-side in ServerConsoles.stopEmulator(), so we do
     * not send RetroSaveStatePacket from here (the Entry is gone by then).
     */
    private void powerOffToMenu() {
        if (closed) return;
        closed = true;
        sendReleaseAll();
        sendAnalogZeros();
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, false));
        PacketDistributor.sendToServer(new RetroPowerOffPacket(consolePos));
        // setScreen triggers removed(): playtime and thumbnail are saved.
        Minecraft.getInstance().setScreen(new CoreSelectScreen(consolePos));
    }

    @Override
    public void onClose() {
        if (closed) return;
        closed = true;
        if (!romId.isEmpty()) {
            PacketDistributor.sendToServer(new RetroSaveStatePacket(consolePos, 0, true, true));
        }
        sendReleaseAll();
        sendAnalogZeros();
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, false));
        super.onClose();
    }

    private void sendAnalogZeros() {
        sentLx = sentLy = sentRx = sentRy = 0;
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, (short) 0, (short) 0, (short) 0, (short) 0));
    }

    private void sendReleaseAll() {
        for (RetroInputBindings.ButtonBind bind : RetroInputBindings.BUTTONS) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, bind.retroId(), false));
        }
    }

    private void snapshotThumb(ClientConsoles.ScreenEntry entry) {
        int[] abgr = entry.lastAbgr();
        if (abgr == null) return;
        int w = entry.width();
        int h = entry.height();
        if (w <= 0 || h <= 0 || abgr.length < w * h) return;
        thumbW = w;
        thumbH = h;
        thumbPixels = abgr.clone(); // once on close — not a hot path
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

    private void blip(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT, pitch));
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