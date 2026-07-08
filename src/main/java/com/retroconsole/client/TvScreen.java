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
 * Нижняя панель: «Выкл» (стоп консоли + выход в CoreSelectScreen), «? Управление»
 * (оверлей справки), название игры по центру, слайдер громкости справа.
 */
public class TvScreen extends Screen {

    /** Высота нижней панели. Единственный источник правды для лейаута. */
    private static final int BAR_HEIGHT = 24;

    // Цвета в стиле CoreSelectScreen — единый вид UI.
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

    public TvScreen(BlockPos consolePos, String romId) {
        super(Component.literal("Retro Console"));
        this.consolePos = consolePos;
        this.romId = romId != null ? romId : "";
        this.displayName = prettyName(this.romId);
    }

    /** «nes/Super_Game.nes» → «Super Game». Пока romId — единственный источник имени. */
    private static String prettyName(String romId) {
        if (romId == null || romId.isEmpty()) return "";
        String name = romId.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('_', ' ');
    }

    // ------------------------------------------------------------------
    // Лейаут панели
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // init() вызывается и при ресайзе окна: виджеты пересоздаются,
        // состояние (showHints) живёт в полях. addViewer на сервере — Set,
        // повторный RetroViewPacket(true) безвреден.
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, true));

        int y = this.height - BAR_HEIGHT + 3;
        int h = BAR_HEIGHT - 6;

        // --- левая сторона ---
        int barLeft = 4;
        barLeft = addLeft(Button.builder(Component.literal("Выкл"), b -> {
            blip(0.8f);
            powerOffToMenu();
        }), barLeft, y, 48, h);
        barLeft = addLeft(Button.builder(Component.literal("? Управление"), b -> {
            blip(1.2f);
            showHints = !showHints;
        }), barLeft, y, 88, h);
        // Новые левые кнопки (ачивки и т.п.) добавлять здесь: barLeft = addLeft(...)

        // --- правая сторона ---
        int barRight = this.width - 4;
        barRight = addRight(new VolumeSlider(0, y, 110, h, SoundPrefs.volume()), barRight);
        // Новые правые виджеты добавлять здесь: barRight = addRight(...)
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

    // ------------------------------------------------------------------
    // Рендер
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Порядок важен: фон → кадр ТВ → панель → виджеты → оверлей справки.
        // super.render() не используется: он повторно рисует затемняющий фон
        // поверх кадра (тот же приём, что в CoreSelectScreen).
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
        // ОПТИМИЗАЦИЯ: ванильный renderBackground рисует мир + блюр каждый кадр.
        // За кадром ТВ этого всё равно не видно — просто чёрная заливка.
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
            // ОПТИМИЗАЦИЯ: snapshotThumb здесь больше НЕ вызывается —
            // раньше это был abgr.clone() (~11 МБ) на КАЖДЫЙ render().
            // Миниатюра снимается один раз в removed().
        } else {
            String noSignal = "No Signal";
            guiGraphics.drawCenteredString(this.font, noSignal, this.width / 2, screenH / 2, 0xFFFFFF);
        }
    }

    private void renderBar(GuiGraphics guiGraphics) {
        guiGraphics.fill(0, this.height - BAR_HEIGHT, this.width, this.height, COL_PANEL_2);
        guiGraphics.fill(0, this.height - BAR_HEIGHT, this.width, this.height - BAR_HEIGHT + 1, COL_EDGE);
        if (!displayName.isEmpty()) {
            // Не наезжаем на кнопки слева (144) и слайдер справа (114).
            int avail = Math.max(40, this.width - 2 * 160);
            String title = this.font.plainSubstrByWidth(displayName, avail);
            guiGraphics.drawCenteredString(this.font, title,
                    this.width / 2, this.height - BAR_HEIGHT / 2 - 4, 0xC8C8C8);
        }
    }

    // ------------------------------------------------------------------
    // Оверлей справки по управлению
    // ------------------------------------------------------------------

    private void renderHintsOverlay(GuiGraphics g) {
        List<String> left = new ArrayList<>();
        left.add("§6Кнопки");
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
        right.add("§6Направления");
        right.add(keysLine(ModKeys.DPAD_UP, ModKeys.DPAD_DOWN, ModKeys.DPAD_LEFT, ModKeys.DPAD_RIGHT, "D-Pad"));
        right.add(stickLine(RetroInputBindings.LEFT_STICK, "L-Stick"));
        right.add(stickLine(RetroInputBindings.RIGHT_STICK, "R-Stick"));
        right.add("");
        right.add("§6Система");
        right.add(line(ModKeys.BTN_START, "Start"));
        right.add(line(ModKeys.BTN_SELECT, "Select"));
        right.add("§eF5§r — сохранить");
        right.add("§eF6§r — загрузить");
        right.add("§eF1§r — эта справка");
        right.add("§eEsc§r — выход");

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

        g.drawString(font, "§e§lУПРАВЛЕНИЕ", x + pad, y + pad, 0xFFFFFF);
        int ty = y + pad + 14;
        for (int i = 0; i < left.size(); i++) {
            g.drawString(font, left.get(i), x + pad, ty + i * 12, 0xE0E0E0);
        }
        for (int i = 0; i < right.size(); i++) {
            g.drawString(font, right.get(i), x + pad + colW1 + gap, ty + i * 12, 0xE0E0E0);
        }
        g.drawString(font, "§8Клавиши меняются в Настройки → Управление. F1 или клик — закрыть",
                x + pad, y + cardH - 12 - 2, 0xFFFFFF);
        g.pose().popPose();
    }

    private static String line(KeyMapping key, String action) {
        return "§e" + key.getTranslatedKeyMessage().getString() + "§r — " + action;
    }

    private static String stickLine(RetroInputBindings.StickBind stick, String label) {
        return keysLine(stick.up(), stick.down(), stick.left(), stick.right(), label);
    }

    private static String keysLine(KeyMapping up, KeyMapping down, KeyMapping left, KeyMapping right,
                                   String label) {
        return "§e" + up.getTranslatedKeyMessage().getString()
                + "/" + down.getTranslatedKeyMessage().getString()
                + "/" + left.getTranslatedKeyMessage().getString()
                + "/" + right.getTranslatedKeyMessage().getString()
                + "§r — " + label;
    }

    // ------------------------------------------------------------------
    // Слайдер громкости
    // ------------------------------------------------------------------

    private static class VolumeSlider extends AbstractSliderButton {
        VolumeSlider(int x, int y, int w, int h, double initial) {
            super(x, y, w, h, Component.empty(), Mth.clamp(initial, 0.0, 1.0));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Громкость: " + (int) Math.round(value * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            // Живое применение при перетаскивании; на диск не пишем.
            ClientAudioHandler.setVolume((float) value);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            SoundPrefs.setVolume((float) value); // сохраняем один раз, при отпускании
        }
    }

    // ------------------------------------------------------------------
    // Жизненный цикл / статистика
    // ------------------------------------------------------------------

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
            // Миниатюра снимается ОДИН раз здесь, а не каждый кадр.
            // removed() выполняется синхронно внутри setScreen — до того, как
            // придёт stop-пакет и dispose() удалит ScreenEntry, так что
            // peekScreen ещё видит последний кадр.
            ClientConsoles.ScreenEntry entry = ClientConsoles.peekScreen(consolePos);
            if (entry != null) snapshotThumb(entry);
            saveThumbnail();
        }
        super.removed();
    }

    // ------------------------------------------------------------------
    // Ввод
    // ------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (showHints) {
                showHints = false;
                return true;
            }
            // БАГФИКС: раньше было minecraft.setScreen(null) — оно НЕ вызывает
            // onClose(), и терялись автосейв, release-all и RetroViewPacket(false).
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
        if (super.mouseClicked(mouseX, mouseY, button)) return true; // кнопки панели работают и при открытой справке
        if (showHints) {
            showHints = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        // БАГФИКС: после клика виджет остаётся сфокусированным, и Enter/Space
        // «нажимали» бы кнопку вместо Start/игры. Сбрасываем фокус после отпускания
        // (не в mouseClicked — иначе сломается перетаскивание слайдера).
        setFocused(null);
        return handled;
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

    // ------------------------------------------------------------------
    // Выключение и закрытие
    // ------------------------------------------------------------------

    /**
     * «Выкл»: остановить консоль на сервере и выйти в меню выбора игры.
     * Автосейв делает сервер внутри ServerConsoles.stopEmulator(), поэтому
     * RetroSaveStatePacket отсюда не шлём (Entry к его приходу уже нет).
     */
    private void powerOffToMenu() {
        if (closed) return;
        closed = true;
        sendReleaseAll();
        sendAnalogZeros();
        PacketDistributor.sendToServer(new RetroViewPacket(consolePos, false));
        PacketDistributor.sendToServer(new RetroPowerOffPacket(consolePos));
        // setScreen вызовет removed(): playtime и тамбнейл сохранятся.
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
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 0, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 0, 1, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 1, 0, (short) 0));
        PacketDistributor.sendToServer(new RetroAnalogPacket(consolePos, 1, 1, (short) 0));
    }

    private void sendReleaseAll() {
        for (RetroInputBindings.ButtonBind bind : RetroInputBindings.BUTTONS) {
            PacketDistributor.sendToServer(new RetroInputPacket(consolePos, bind.retroId(), false));
        }
    }

    // ------------------------------------------------------------------
    // Тамбнейлы
    // ------------------------------------------------------------------

    private void snapshotThumb(ClientConsoles.ScreenEntry entry) {
        int[] abgr = entry.lastAbgr();
        if (abgr == null) return;
        int w = entry.width();
        int h = entry.height();
        if (w <= 0 || h <= 0 || abgr.length < w * h) return;
        thumbW = w;
        thumbH = h;
        thumbPixels = abgr.clone(); // один раз при закрытии — не горячий путь
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

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

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