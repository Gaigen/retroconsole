package com.retroconsole.client;

import com.retroconsole.config.RetroConfigJson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * In-game editor for {@code config/retroconsole.json}.
 *
 * <p>Loader-agnostic on purpose: the rows are a small declarative table over
 * {@link RetroConfigJson}'s fields, so nothing here is loader specific and the
 * same screen is opened by the library gear button and by the mods-menu config
 * button on every loader.
 *
 * <p>Edits go straight into the live config object (which is what
 * {@code RetroSettings} reads), and {@code Done} persists the file.
 */
public final class ConfigScreen extends Screen {

    private static final String KEY = "retroconsole.configuration.";
    private static final int TABS_Y = 26;
    private static final int PATH_Y = 50;
    private static final int TOP = 66;
    private static final int ROW_H = 22;
    private static final int FOOTER = 34;
    private static final int WIDGET_W = 168;

    private enum Tab { PATHS, STREAMING, LIMITS, VIDEO, INPUT }

    private final Screen parent;
    private Tab tab = Tab.STREAMING;
    private int page;
    private List<Row> visible = List.of();
    private final List<AbstractWidget> widgets = new ArrayList<>();

    public ConfigScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
    }

    /** Opener signature used by {@code ConfigAccess}. */
    public static void open(Minecraft minecraft, Screen parent) {
        minecraft.setScreen(new ConfigScreen(parent));
    }

    // ----- layout ----------------------------------------------------------

    @Override
    protected void init() {
        int tabW = (width - 24) / Tab.values().length - 2;
        int x = 12;
        for (Tab t : Tab.values()) {
            Tab mine = t;
            Button b = addRenderableWidget(Button.builder(Component.translatable(KEY + t.name().toLowerCase(Locale.ROOT)),
                            btn -> {
                                tab = mine;
                                page = 0;
                                rebuildWidgets();
                            })
                    .bounds(x, TABS_Y, tabW, 18)
                    .build());
            b.active = t != tab;
            x += tabW + 2;
        }

        List<Row> rows = rows(tab);
        int perPage = perPage();
        int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        page = Mth.clamp(page, 0, pages - 1);
        int from = page * perPage;
        int to = Math.min(rows.size(), from + perPage);
        visible = rows.subList(from, to);

        int widgetX = width - 12 - WIDGET_W;
        widgets.clear();
        for (int i = 0; i < visible.size(); i++) {
            AbstractWidget widget = addRenderableWidget(
                    visible.get(i).create(widgetX, TOP + i * ROW_H, WIDGET_W, 18));
            widgets.add(widget);
        }

        int doneW = 100;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds((width - doneW) / 2, height - 26, doneW, 18)
                .build());

        if (pages > 1) {
            Button prev = Button.builder(Component.literal("<"), b -> {
                        page--;
                        rebuildWidgets();
                    })
                    .bounds(width - 92, height - 26, 18, 18).build();
            prev.active = page > 0;
            addRenderableWidget(prev);
            Button next = Button.builder(Component.literal(">"), b -> {
                        page++;
                        rebuildWidgets();
                    })
                    .bounds(width - 70, height - 26, 18, 18).build();
            next.active = page < pages - 1;
            addRenderableWidget(next);
        }
    }

    private int perPage() {
        return Math.max(1, (height - TOP - FOOTER) / ROW_H);
    }

    // ----- rows ------------------------------------------------------------

    private interface Row {
        Component label();

        AbstractWidget create(int x, int y, int w, int h);
    }

    private record TableRow(String labelKey, WidgetFactory factory) implements Row {
        @Override
        public Component label() {
            return Component.translatable(KEY + labelKey);
        }

        @Override
        public AbstractWidget create(int x, int y, int w, int h) {
            return factory.create(x, y, w, h);
        }
    }

    @FunctionalInterface
    private interface WidgetFactory {
        AbstractWidget create(int x, int y, int w, int h);
    }

    private List<Row> rows(Tab which) {
        RetroConfigJson c = RetroConfigJson.get();
        List<Row> rows = new ArrayList<>();
        switch (which) {
            case PATHS -> {
                rows.add(text("coresDir", () -> c.paths.coresDir, v -> c.paths.coresDir = v));
                rows.add(text("romsDir", () -> c.paths.romsDir, v -> c.paths.romsDir = v));
                rows.add(text("systemDir", () -> c.paths.systemDir, v -> c.paths.systemDir = v));
                rows.add(text("saveDir", () -> c.paths.saveDir, v -> c.paths.saveDir = v));
                rows.add(text("artDir", () -> c.paths.artDir, v -> c.paths.artDir = v));
            }
            case STREAMING -> {
                rows.add(int_("videoDistance", 8, 256, 1,
                        () -> c.streaming.videoDistance, v -> c.streaming.videoDistance = v));
                rows.add(int_("audioDistance", 4, 256, 1,
                        () -> c.streaming.audioDistance, v -> c.streaming.audioDistance = v));
                rows.add(int_("viewSubscribeDistance", 8, 256, 1,
                        () -> c.streaming.viewSubscribeDistance, v -> c.streaming.viewSubscribeDistance = v));
                rows.add(int_("controlDistance", 2, 64, 1,
                        () -> c.streaming.controlDistance, v -> c.streaming.controlDistance = v));
                rows.add(int_("notifyDistance", 32, 512, 16,
                        () -> c.streaming.notifyDistance, v -> c.streaming.notifyDistance = v));
                rows.add(int_("worldMaxWidth", 160, 1920, 8,
                        () -> c.streaming.worldMaxWidth, v -> c.streaming.worldMaxWidth = v));
            }
            case LIMITS -> {
                rows.add(int_("maxCoreSlots", 0, 64, 1,
                        () -> c.limits.maxCoreSlots, v -> c.limits.maxCoreSlots = v));
                rows.add(int_("maxPcsx2Sessions", 1, 8, 1,
                        () -> c.limits.maxPcsx2Sessions, v -> c.limits.maxPcsx2Sessions = v));
                rows.add(int_("maxScreenCluster", 4, 4096, 4,
                        () -> c.limits.maxScreenCluster, v -> c.limits.maxScreenCluster = v));
                rows.add(int_("batteryAutosaveSeconds", 5, 600, 5,
                        () -> c.limits.batteryAutosaveSeconds, v -> c.limits.batteryAutosaveSeconds = v));
                rows.add(bool("enable3ds", () -> c.limits.enable3ds, v -> c.limits.enable3ds = v));
            }
            case VIDEO -> {
                rows.add(preset(() -> c.video.preset, v -> c.video.preset = v));
                rows.add(bool("doom3InvertY", () -> c.video.doom3InvertY, v -> c.video.doom3InvertY = v));
                rows.add(text("flycastInternalResolution",
                        () -> c.video.flycastInternalResolution, v -> c.video.flycastInternalResolution = v));
                rows.add(text("flycastTexUpscale",
                        () -> c.video.flycastTexUpscale, v -> c.video.flycastTexUpscale = v));
                rows.add(text("flycastAnisotropic",
                        () -> c.video.flycastAnisotropic, v -> c.video.flycastAnisotropic = v));
                rows.add(text("ppssppInternalResolution",
                        () -> c.video.ppssppInternalResolution, v -> c.video.ppssppInternalResolution = v));
                rows.add(text("ppssppTextureScaling",
                        () -> c.video.ppssppTextureScaling, v -> c.video.ppssppTextureScaling = v));
                rows.add(text("pcsx2UpscaleMultiplier",
                        () -> c.video.pcsx2UpscaleMultiplier, v -> c.video.pcsx2UpscaleMultiplier = v));
                rows.add(text("pcsx2Anisotropic",
                        () -> c.video.pcsx2Anisotropic, v -> c.video.pcsx2Anisotropic = v));
                rows.add(text("citraResolution",
                        () -> c.video.citraResolution, v -> c.video.citraResolution = v));
                rows.add(text("doom3Resolution",
                        () -> c.video.doom3Resolution, v -> c.video.doom3Resolution = v));
            }
            case INPUT -> {
                rows.add(bool("gamepad.enabled", () -> c.input.gamepadEnabled, v -> c.input.gamepadEnabled = v));
                rows.add(dec("gamepad.deadzone", 0.0, 0.9, 0.05,
                        () -> c.input.gamepadDeadzone, v -> c.input.gamepadDeadzone = v));
                rows.add(dec("gamepad.triggerThreshold", 0.05, 0.95, 0.05,
                        () -> c.input.gamepadTriggerThreshold, v -> c.input.gamepadTriggerThreshold = v));
            }
        }
        return rows;
    }

    private Row bool(String labelKey, BooleanSupplier get, Consumer<Boolean> set) {
        return new TableRow(labelKey, (x, y, w, h) -> CycleButton.onOffBuilder(get.getAsBoolean())
                .create(x, y, w, h, Component.empty(), (button, value) -> set.accept(value)));
    }

    private Row preset(Supplier<String> get, Consumer<String> set) {
        return new TableRow("preset", (x, y, w, h) -> {
            String current = get.get();
            if (!"performance".equals(current) && !"quality".equals(current)) {
                current = "balanced";
            }
            return CycleButton
                    .builder((String v) -> Component.translatable(KEY + "preset." + v))
                    .withValues("performance", "balanced", "quality")
                    .withInitialValue(current)
                    .create(x, y, w, h, Component.empty(), (button, value) -> set.accept(value));
        });
    }

    private Row int_(String labelKey, int min, int max, int step, IntSupplier get, IntConsumer set) {
        return new TableRow(labelKey, (x, y, w, h) ->
                new IntSlider(x, y, w, h, min, max, step, get.getAsInt(), set));
    }

    private Row dec(String labelKey, double min, double max, double step,
                    DoubleSupplier get, DoubleConsumer set) {
        return new TableRow(labelKey, (x, y, w, h) ->
                new DoubleSlider(x, y, w, h, min, max, step, get.getAsDouble(), set));
    }

    private Row text(String labelKey, Supplier<String> get, Consumer<String> set) {
        return new TableRow(labelKey, (x, y, w, h) -> {
            EditBox box = new EditBox(font, x, y, w, h, Component.translatable(KEY + labelKey));
            box.setMaxLength(128);
            box.setValue(get.get());
            box.setResponder(set);
            return box;
        });
    }

    // ----- rendering -------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g, mx, my, partialTick);
        g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        // Below the tab row on purpose: the tabs are 18 px tall and used to sit
        // on top of this line at GUI scale 3.
        g.drawCenteredString(font, Component.literal(elide(RetroConfigJson.file().toString(), width - 24)),
                width / 2, PATH_Y, 0x707880);

        int widgetX = width - 12 - WIDGET_W;
        int labelW = widgetX - 24;
        for (int i = 0; i < visible.size() && i < widgets.size(); i++) {
            int y = TOP + i * ROW_H;
            // Sliders show only their value, so every row keeps its name here.
            g.drawString(font, elide(visible.get(i).label().getString(), labelW),
                    14, y + 5, 0xC8C8C8, false);
        }
        super.render(g, mx, my, partialTick);
    }

    /** Truncates to {@code maxW} and marks the cut with an ellipsis. */
    private String elide(String text, int maxW) {
        if (maxW <= 0 || font.width(text) <= maxW) {
            return text;
        }
        String dots = "...";
        int room = Math.max(0, maxW - font.width(dots));
        return font.plainSubstrByWidth(text, room) + dots;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        g.fill(0, 0, width, height, 0xE00C0E12);
    }

    @Override
    public void onClose() {
        RetroConfigJson.get().save();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ----- widgets ---------------------------------------------------------

    private static final class IntSlider extends AbstractSliderButton {

        private final int min;
        private final int max;
        private final int step;
        private final IntConsumer setter;

        IntSlider(int x, int y, int w, int h, int min, int max, int step,
                  int value, IntConsumer setter) {
            super(x, y, w, h, Component.empty(), fraction(min, max, value));
            this.min = min;
            this.max = max;
            this.step = step;
            this.setter = setter;
            updateMessage();
        }

        private static double fraction(int min, int max, int value) {
            return max == min ? 0.0 : Mth.clamp((double) (value - min) / (max - min), 0.0, 1.0);
        }

        private int snap() {
            int raw = min + (int) Math.round(value * (max - min));
            if (step > 1) {
                raw = min + (int) (Math.round((raw - min) / (double) step) * step);
            }
            return Mth.clamp(raw, min, max);
        }

        // The row name sits to the left of every widget, so the slider only has
        // to show the value — a full caption overflows the box at GUI scale 3.
        @Override
        protected void updateMessage() {
            setMessage(Component.literal(Integer.toString(snap())));
        }

        @Override
        protected void applyValue() {
            setter.accept(snap());
            updateMessage();
        }
    }

    private static final class DoubleSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final double step;
        private final DoubleConsumer setter;

        DoubleSlider(int x, int y, int w, int h, double min, double max, double step,
                     double value, DoubleConsumer setter) {
            super(x, y, w, h, Component.empty(),
                    max == min ? 0.0 : Mth.clamp((value - min) / (max - min), 0.0, 1.0));
            this.min = min;
            this.max = max;
            this.step = step;
            this.setter = setter;
            updateMessage();
        }

        private double snap() {
            double raw = min + value * (max - min);
            raw = min + Math.round((raw - min) / step) * step;
            return Mth.clamp(raw, min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(String.format(Locale.ROOT, "%.2f", snap())));
        }

        @Override
        protected void applyValue() {
            setter.accept(snap());
            updateMessage();
        }
    }
}
