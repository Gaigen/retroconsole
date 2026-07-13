package com.retroconsole.client.bezel;

import com.retroconsole.client.ModTexts;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Bezel art picker: file list with live preview.
 * LMB on file — left panel, RMB — right panel. Applied immediately.
 */
public class BezelSettingsScreen extends Screen {

    private static final int ITEM_H = 18;
    private static final int COL_PANEL   = 0xFF101318;
    private static final int COL_PANEL_2 = 0xFF14161C;
    private static final int COL_EDGE    = 0xFF2A2F3A;
    private static final int COL_ROW_A   = 0xFF12151C;
    private static final int COL_ROW_B   = 0xFF14171F;
    private static final int COL_HOVER   = 0xFF1B1F29;
    private static final int COL_SEL     = 0xFF232B45;

    private final Screen parent;
    private List<String> files = List.of();
    private int scroll;

    public BezelSettingsScreen(Screen parent) {
        super(ModTexts.c("bezel.title"));
        this.parent = parent;
    }

    private int listX() { return 10; }
    private int listW() { return width / 2 - 18; }
    private int listTop() { return 46; }
    private int listViewH() { return ((height - listTop() - 32) / ITEM_H) * ITEM_H; }
    private int rightX() { return width / 2 + 2; }
    private int rightW() { return width - rightX() - 10; }

    @Override
    protected void init() {
        refreshFiles();

        int rx = rightX();
        int rw = rightW();
        int y = height - 118;

        addRenderableWidget(CycleButton.onOffBuilder(TvBezelPrefs.enabled())
                .create(rx, y, rw, 18, ModTexts.c("bezel.enabled"),
                        (b, v) -> TvBezelPrefs.setEnabled(v)));
        y += 22;

        addRenderableWidget(CycleButton.builder(
                        (TvBezelPrefs.FitMode m) ->
                                ModTexts.c("bezel.fit." + m.name().toLowerCase(Locale.ROOT)))
                .withValues(TvBezelPrefs.FitMode.FIT, TvBezelPrefs.FitMode.FILL, TvBezelPrefs.FitMode.STRETCH)
                .withInitialValue(TvBezelPrefs.fitMode())
                .create(rx, y, rw, 18, ModTexts.c("bezel.fit"),
                        (b, v) -> TvBezelPrefs.setFitMode(v)));
        y += 22;

        addRenderableWidget(new OpacitySlider(rx, y, rw, 18));
        y += 22;

        int half = (rw - 4) / 2;
        addRenderableWidget(Button.builder(ModTexts.c("bezel.open_folder"),
                        b -> Util.getPlatform().openFile(TvBezelPrefs.bezelDir().toFile()))
                .bounds(rx, y, half, 18).build());
        addRenderableWidget(Button.builder(ModTexts.c("bezel.refresh"), b -> {
            TvBezelRenderer.invalidateAll();
            refreshFiles();
            rebuildWidgets();
        }).bounds(rx + half + 4, y, half, 18).build());
        y += 26;

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(rx + (rw - 100) / 2, y, 100, 18).build());
    }

    private void refreshFiles() {
        files = TvBezelPrefs.listArtOptions();
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, files.size() - listViewH() / ITEM_H);
    }

    private class OpacitySlider extends AbstractSliderButton {
        OpacitySlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), TvBezelPrefs.opacity());
            updateMessage();
        }

        @Override protected void updateMessage() {
            setMessage(ModTexts.c("bezel.opacity", (int) Math.round(value * 100)));
        }

        @Override protected void applyValue() {
            TvBezelPrefs.setOpacity((float) value);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        g.fill(0, 0, width, 20, COL_PANEL_2);
        g.fill(0, 19, width, 20, COL_EDGE);
        g.drawCenteredString(font, title, width / 2, 6, 0xFFFFFF);
        String folder = TvBezelPrefs.bezelDir().toAbsolutePath().toString();
        g.drawCenteredString(font, font.plainSubstrByWidth(folder, width - 20), width / 2, 23, 0x707880);
        g.drawCenteredString(font, ModTexts.s("bezel.assign_hint"), width / 2, 34, 0x9098A0);

        renderList(g, mx, my);
        renderPreviews(g);

        for (var renderable : renderables) renderable.render(g, mx, my, pt);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xE00C0E12);
    }

    private void renderList(GuiGraphics g, int mx, int my) {
        int x = listX(), w = listW(), top = listTop(), viewH = listViewH();
        g.fill(x - 2, top - 3, x + w + 2, top + viewH + 3, COL_PANEL);
        g.fill(x - 2, top - 3, x + w + 2, top - 2, COL_EDGE);

        String left = TvBezelPrefs.fileNameForSide(TvBezelPrefs.leftPath());
        String right = TvBezelPrefs.fileNameForSide(TvBezelPrefs.rightPath());

        for (int i = scroll; i < files.size(); i++) {
            int y = top + (i - scroll) * ITEM_H;
            if (y > top + viewH - ITEM_H) break;
            String name = files.get(i);
            boolean isNone = name.isEmpty();
            boolean asLeft = name.equals(left);
            boolean asRight = name.equals(right);
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + ITEM_H;

            g.fill(x, y, x + w, y + ITEM_H - 1,
                    (asLeft || asRight) ? COL_SEL : hover ? COL_HOVER : (i % 2 == 0 ? COL_ROW_A : COL_ROW_B));

            int tx = x + 6;
            String badge = asLeft && asRight ? "L+R" : asLeft ? "L" : asRight ? "R" : "";
            if (!badge.isEmpty()) {
                int bw = font.width(badge) + 6;
                g.fill(tx, y + 2, tx + bw, y + ITEM_H - 3, 0xFF3C64C8);
                g.drawString(font, badge, tx + 3, y + 5, 0xFFFFFFFF, false);
                tx += bw + 5;
            }
            String label = isNone ? ModTexts.s("bezel.none") : name;
            g.drawString(font, font.plainSubstrByWidth(label, x + w - tx - 6), tx, y + 5,
                    hover ? 0xFFFFFF : isNone ? 0x9098A0 : 0xC8C8C8);
        }

        int contentH = files.size() * ITEM_H;
        if (contentH > viewH) {
            int barH = Math.max(12, viewH * viewH / contentH);
            int barY = top + (int) ((long) scroll * ITEM_H * (viewH - barH) / (contentH - viewH));
            g.fill(x + w + 2, top, x + w + 5, top + viewH, 0x30FFFFFF);
            g.fill(x + w + 2, barY, x + w + 5, barY + barH, 0xC0AAAAAA);
        }
        if (files.size() <= 1) {
            g.drawCenteredString(font, ModTexts.s("bezel.empty"), x + w / 2, top + 24, 0x808890);
        }
    }

    private void renderPreviews(GuiGraphics g) {
        int rx = rightX(), rw = rightW();
        int top = listTop() + 12;
        int ph = Math.max(40, height - 132 - top);
        int half = (rw - 8) / 2;
        drawPreview(g, rx, top, half, ph, TvBezelPrefs.leftPath(), ModTexts.s("bezel.preview.left"));
        drawPreview(g, rx + half + 8, top, half, ph, TvBezelPrefs.rightPath(), ModTexts.s("bezel.preview.right"));
    }

    private void drawPreview(GuiGraphics g, int x, int y, int w, int h, Path p, String label) {
        g.drawString(font, label, x, y - 10, 0x9098A0);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, COL_EDGE);
        g.fill(x, y, x + w, y + h, 0xFF07090C);
        if (p == null) {
            g.drawCenteredString(font, ModTexts.s("bezel.none"), x + w / 2, y + h / 2 - 4, 0x606870);
            return;
        }
        TvBezelRenderer.Preview tex = TvBezelRenderer.preview(p, Util.getMillis());
        if (tex == null) return;
        float scale = Math.min((float) w / tex.w(), (float) h / tex.h());
        int dw = Math.max(1, Math.round(tex.w() * scale));
        int dh = Math.max(1, Math.round(tex.h() * scale));
        g.blit(tex.id(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh,
                0f, 0f, tex.w(), tex.h(), tex.w(), tex.h());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        int x = listX(), w = listW(), top = listTop(), viewH = listViewH();
        if (mx >= x && mx < x + w && my >= top && my < top + viewH) {
            int idx = (int) ((my - top) / ITEM_H) + scroll;
            if (idx >= 0 && idx < files.size()) {
                String name = files.get(idx);
                if (button == 1) TvBezelPrefs.setRightFile(name);
                else TvBezelPrefs.setLeftFile(name);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double hy) {
        scroll = Mth.clamp(scroll - (int) Math.signum(hy), 0, maxScroll());
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
