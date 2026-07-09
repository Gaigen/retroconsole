package com.retroconsole.client;

import com.retroconsole.library.GameSystem;
import com.retroconsole.client.library.ClientPlayerData;
import com.retroconsole.client.library.PlayStats;
import com.retroconsole.library.RomLibrary;
import com.retroconsole.client.library.SaveStates;
import com.retroconsole.network.RetroCoreSelectPacket;
import com.retroconsole.network.RetroLibraryPacket;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class CoreSelectScreen extends Screen {

    private enum View { CARDS, LIST, MANUAL }

    private static final Path ART_DIR = ArtGenerator.ART_DIR;
    private static final int ITEM_H = 16;
    private static final int CARD_H = 88;

    private static final int COL_PANEL   = 0xFF101318;
    private static final int COL_PANEL_2 = 0xFF14161C;
    private static final int COL_EDGE    = 0xFF2A2F3A;
    private static final int COL_ROW_A   = 0xFF12151C;
    private static final int COL_ROW_B   = 0xFF14171F;
    private static final int COL_HOVER   = 0xFF1B1F29;
    private static final int COL_SEL     = 0xFF232B45;

    private final BlockPos consolePos;
    private final RomLibrary lib = new RomLibrary();

    private View view = View.CARDS;
    private final List<GameSystem> tabs = new ArrayList<>();
    private GameSystem activeTab = null;

    private List<RomLibrary.Rom> shown = new ArrayList<>();
    private RomLibrary.Rom selectedRom, heroRom;
    private RomLibrary.Core selectedCore;
    private final Map<String, String> overrides = new HashMap<>();       // romId -> coreId
    private final Map<String, String> systemOverrides = new HashMap<>(); // systemId -> coreId

    private double scrollRoms, targetRoms, scrollCores, targetCores, scrollCards, targetCards;
    private int pickerScroll;

    private EditBox searchBox;
    private Button startBtn, pickCoreBtn, backBtn, modeBtn, folderBtn;
    private boolean showHelp, showCorePicker;
    private boolean serverLibrary;
    private boolean libraryLoading;
    private long lastClickTime;
    private RomLibrary.Rom lastClicked;

    private GameSystem popupSystem;
    private List<RomLibrary.Rom> popupRoms = List.of();
    private int popupScroll;

    public CoreSelectScreen(BlockPos consolePos) {
        super(Component.literal("Retro Console"));
        this.consolePos = consolePos;
    }

    public BlockPos consolePos() {
        return consolePos;
    }

    @Override
    protected void init() {
        super.init();
        serverLibrary = ClientLibraryCache.useServerLibrary();

        backBtn = addRenderableWidget(Button.builder(Component.literal("⌂"), b -> {
            view = View.CARDS;
            modeBtn.setMessage(Component.literal(viewLabel()));
            blip(1.2f);
        }).pos(10, 22).size(20, 18).build());

        searchBox = new EditBox(font, 36, 24, 140, 16, Component.literal("Поиск"));
        searchBox.setHint(Component.literal("Поиск…"));
        searchBox.setResponder(s -> refreshShown());
        addRenderableWidget(searchBox);

        modeBtn = addRenderableWidget(Button.builder(Component.literal(viewLabel()), b -> {
            view = switch (view) {
                case CARDS -> View.LIST;
                case LIST -> View.MANUAL;
                case MANUAL -> View.CARDS;
            };
            b.setMessage(Component.literal(viewLabel()));
            blip(1.2f);
            refreshShown();
        }).pos(width - 148, 22).size(64, 18).build());

        folderBtn = addRenderableWidget(Button.builder(Component.literal("Папка"),
                        b -> Util.getPlatform().openFile(RomLibrary.ensureDir(currentFolder()).toFile()))
                .pos(width - 80, 22).size(44, 18).build());
        folderBtn.active = !serverLibrary;

        addRenderableWidget(Button.builder(Component.literal("?"), b -> showHelp = !showHelp)
                .pos(width - 32, 22).size(20, 18).build());

        pickCoreBtn = addRenderableWidget(Button.builder(Component.literal("Ядро…"),
                        b -> { if (selectedRom != null) { showCorePicker = true; pickerScroll = 0; } })
                .pos(width - 86, height - 51).size(76, 18).build());

        startBtn = addRenderableWidget(Button.builder(Component.literal("▶ Запустить"),
                        b -> startEmulator())
                .pos(width / 2 - 60, height - 26).size(120, 20).build());

        if (serverLibrary) {
            libraryLoading = true;
            ClientLibraryCache.request(consolePos);
        } else {
            lib.scan();
            onLibraryReady();
        }
    }

    /** Вызывается из ClientPacketHandlers при получении каталога с сервера. */
    public void onServerLibraryReceived(RetroLibraryPacket pkt) {
        if (!pkt.consolePos().equals(consolePos)) return;
        lib.loadFromNetwork(pkt);
        libraryLoading = false;
        onLibraryReady();
    }

    @Override
    public void tick() {
        super.tick();
        if (libraryLoading) {
            ClientLibraryCache.poll(consolePos).ifPresent(this::onServerLibraryReceived);
        }
    }

    private void onLibraryReady() {
        rebuildTabs();
        loadPrefs();
        String heroId = PlayStats.lastPlayedRomId();
        heroRom = heroId == null ? null
                : lib.roms.stream().filter(r -> r.id().equals(heroId)).findFirst().orElse(null);
        if (heroRom != null) TextureCache.invalidate(SaveStates.thumbFor(heroRom.id()));
        for (GameSystem s : tabs) ArtGenerator.ensure(s);
        refreshShown();
    }

    private String viewLabel() {
        return switch (view) { case CARDS -> "Консоли"; case LIST -> "Список"; case MANUAL -> "Ручной"; };
    }

    // ---------- данные ----------

    private void rebuildTabs() {
        tabs.clear();
        for (GameSystem s : GameSystem.all()) {
            boolean hasGames = lib.roms.stream().anyMatch(r -> r.system() == s);
            boolean hasCores = lib.cores.stream().anyMatch(c -> c.system() == s);
            if (hasGames || hasCores) tabs.add(s);
        }
        if (activeTab != null && !tabs.contains(activeTab)) activeTab = null;
    }

    private void refreshShown() {
        String q = searchBox == null ? "" : searchBox.getValue().toLowerCase(Locale.ROOT).trim();
        shown = lib.roms.stream()
                .filter(r -> view != View.LIST || activeTab == null || r.system() == activeTab)
                .filter(r -> q.isEmpty() || r.displayName().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator
                        .comparing((RomLibrary.Rom r) -> !PlayStats.favorite(r.id()))
                        .thenComparing(r -> r.displayName().toLowerCase(Locale.ROOT)))
                .toList();
        targetRoms = scrollRoms = 0;
        if (selectedRom != null && !shown.contains(selectedRom)) selectedRom = null;
    }

    private RomLibrary.Core coreById(String id) {
        if (id == null) return null;
        for (RomLibrary.Core c : lib.cores) if (c.id().equals(id)) return c;
        return null;
    }

    private RomLibrary.Core coreForSystem(GameSystem s) {
        RomLibrary.Core c = coreById(systemOverrides.get(s.id));
        if (c != null) return c;
        for (String hint : s.coreHints)
            for (RomLibrary.Core core : lib.cores)
                if (core.id().toLowerCase(Locale.ROOT).contains(hint)) return core;
        return null;
    }

    /** Ядро для игры: выбор игрока для файла → выбор для системы → приоритеты системы. */
    private RomLibrary.Core coreFor(RomLibrary.Rom rom) {
        if (rom == null) return null;
        RomLibrary.Core c = coreById(overrides.get(rom.id()));
        return c != null ? c : coreForSystem(rom.system());
    }

    private Path currentFolder() {
        if (view == View.LIST && activeTab != null && activeTab != GameSystem.OTHER)
            return RomLibrary.romsDir().resolve(activeTab.folder);
        return RomLibrary.romsDir();
    }

    private void startEmulator() {
        startEmulator(false);
    }

    private void startEmulator(boolean loadAuto) {
        if (selectedRom == null) return;
        RomLibrary.Core core = view == View.MANUAL ? selectedCore : coreFor(selectedRom);
        if (core == null) {
            if (view != View.MANUAL && !lib.cores.isEmpty()) {
                showCorePicker = true;
                pickerScroll = 0;
                blip(1.2f);
            }
            return;
        }
        savePrefs();
        PlayStats.onLaunch(selectedRom.id());
        blip(2.0f);
        PacketDistributor.sendToServer(
                new RetroCoreSelectPacket(consolePos, core.id(), selectedRom.id(), loadAuto));
        Minecraft.getInstance().setScreen(new TvScreen(consolePos, selectedRom.id()));
    }

    private List<RomLibrary.Rom> romsOf(GameSystem s) {
        return lib.roms.stream().filter(r -> r.system() == s)
                .sorted(Comparator
                        .comparing((RomLibrary.Rom r) -> !PlayStats.favorite(r.id()))
                        .thenComparing(r -> r.displayName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private void openPopup(GameSystem s) {
        popupSystem = s;
        popupRoms = romsOf(s);
        popupScroll = 0;
        selectedRom = popupRoms.isEmpty() ? null : popupRoms.get(0);
        blip(1.2f);
    }

    /** Геометрия попапа: [x, y, w, h, видимых строк]. */
    private int[] popupGeom() {
        int w = Math.min(width - 60, 440);
        int visible = Math.max(3, Math.min(Math.max(popupRoms.size(), 1), (height - 140) / ITEM_H));
        int h = visible * ITEM_H + 66;
        return new int[]{(width - w) / 2, (height - h) / 2, w, h, visible};
    }

    private void renderGamePopup(GuiGraphics g, int mx, int my) {
        int[] geo = popupGeom();
        int x = geo[0], y = geo[1], w = geo[2], h = geo[3], visible = geo[4];
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0xB0000000);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF3C4250);
        g.fill(x, y, x + w, y + h, COL_PANEL_2);
        g.fill(x, y, x + w, y + 2, popupSystem.color);

        long total = popupRoms.stream().mapToLong(r -> PlayStats.playtimeSec(r.id())).sum();
        g.drawString(font, "§l" + popupSystem.fullName, x + 12, y + 10, 0xFFFFFF);
        String hdr = popupRoms.size() + " " + plural(popupRoms.size())
                + (total > 0 ? " • " + PlayStats.formatPlaytime(total) : "");
        g.drawString(font, hdr, x + w - font.width(hdr) - 12, y + 10, 0x9098A0);

        int listY = y + 26;
        for (int i = popupScroll; i < popupRoms.size() && i < popupScroll + visible; i++) {
            int ry = listY + (i - popupScroll) * ITEM_H;
            RomLibrary.Rom rom = popupRoms.get(i);
            boolean sel = rom.equals(selectedRom);
            boolean hover = mx >= x + 6 && mx < x + w - 6 && my >= ry && my < ry + ITEM_H;
            boolean playable = coreFor(rom) != null;
            boolean fav = PlayStats.favorite(rom.id());
            g.fill(x + 6, ry, x + w - 6, ry + ITEM_H - 1,
                    sel ? COL_SEL : hover ? COL_HOVER : (i % 2 == 0 ? COL_ROW_A : COL_ROW_B));
            g.fill(x + 6, ry, x + 9, ry + ITEM_H - 1, playable ? popupSystem.color : 0xFF404040);

            long pt = PlayStats.playtimeSec(rom.id());
            String right = (SaveStates.hasSave(rom.id()) ? "§a●§r " : "")
                    + (pt > 0 ? "§7" + PlayStats.formatPlaytime(pt) + "§r  " : "")
                    + "§8" + formatSize(rom.size());
            int rightW = font.width(right);
            String name = font.plainSubstrByWidth(rom.displayName(),
                    w - 40 - rightW - (fav ? 12 : 0));
            int color = !playable ? 0x707070 : sel ? 0x80FF90 : hover ? 0xFFFFFF : 0xC8C8C8;
            g.drawString(font, (fav ? "§e★ §r" : "") + name, x + 14, ry + 4, color);
            g.drawString(font, right, x + w - rightW - 10, ry + 4, 0xFFFFFF);
        }
        if (popupRoms.isEmpty())
            g.drawCenteredString(font, serverLibrary
                            ? "На сервере нет игр в roms/" + popupSystem.folder
                            : "Пусто. Закиньте игры в roms/" + popupSystem.folder,
                    x + w / 2, listY + 16, 0x808890);

        RomLibrary.Core core = coreFor(selectedRom);
        boolean hasSave = selectedRom != null && SaveStates.hasSave(selectedRom.id());
        String foot = selectedRom == null ? "§8Esc — закрыть"
                : core != null ? (hasSave
                    ? "§aEnter — продолжить §8• Shift+Enter — новая игра §8• ★ ПКМ • Esc — закрыть"
                    : "§aEnter / двойной клик — запуск §8• ★ ПКМ • Esc — закрыть")
                : "§6Enter — выбрать ядро §8• Esc — закрыть";
        g.drawString(font, foot, x + 12, y + h - 16, 0xFFFFFF);
        g.pose().popPose();
    }

    // ---------- рендер ----------

    private int listTop() { return view == View.LIST ? 70 : 48; }
    private int listHeight() { return ((height - listTop() - 58) / ITEM_H) * ITEM_H; }
    private int cardCols() { return Math.max(2, (width - 20) / 168); }
    private int cardW() { return (width - 20 - (cardCols() - 1) * 8) / cardCols(); }
    private int cardsTop() { return 54 + (heroRom == null ? 0 : 76); }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xE00C0E12);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        scrollRoms = approach(scrollRoms, targetRoms);
        scrollCores = approach(scrollCores, targetCores);
        scrollCards = approach(scrollCards, targetCards);

        boolean cards = view == View.CARDS;
        searchBox.visible = !cards;
        backBtn.visible = !cards;
        startBtn.visible = !cards;
        if (cards) pickCoreBtn.visible = false;

        renderBackground(g, mx, my, pt);
        g.fill(0, 0, width, 46, COL_PANEL_2);
        g.fill(0, 45, width, 46, COL_EDGE);
        g.drawString(font, "§lRETRO CONSOLE", 10, 8, 0xFFE0E0E0);
        if (libraryLoading) {
            g.drawCenteredString(font, "Загрузка библиотеки с сервера…", width / 2, height / 2, 0xC0C8D0);
            for (Renderable renderable : this.renderables) {
                renderable.render(g, mx, my, pt);
            }
            return;
        }
        if (cards) {
            String stats = lib.roms.size() + " " + plural(lib.roms.size())
                    + " • " + lib.cores.size() + " ядер";
            if (serverLibrary) stats += " §8(сервер)";
            g.drawString(font, "§8" + stats, 110, 8, 0xFFFFFF);
        }

        switch (view) {
            case CARDS -> renderCards(g, mx, my);
            case LIST -> {
                renderTabs(g, mx, my);
                renderPanel(g);
                renderGameList(g, mx, my, 10, width - 20);
                renderInfoBar(g);
            }
            case MANUAL -> {
                renderPanel(g);
                renderCoreList(g, mx, my);
                renderGameList(g, mx, my, width / 2 + 8, width / 2 - 18);
                renderInfoBar(g);
            }
        }

        for (Renderable renderable : this.renderables) {
            renderable.render(g, mx, my, pt);
        }
        if (view == View.CARDS) renderCardTextures(g);
        if (popupSystem != null && view == View.CARDS) renderGamePopup(g, mx, my);
        if (showHelp) renderHelp(g);
        if (showCorePicker) renderCorePicker(g, mx, my);
    }

    private void renderPanel(GuiGraphics g) {
        int top = listTop(), viewH = listHeight();
        g.fill(8, top - 3, width - 8, top + viewH + 3, COL_PANEL);
        g.fill(8, top - 3, width - 8, top - 2, COL_EDGE);
    }

    // ---------- карточки ----------

    private void renderCards(GuiGraphics g, int mx, int my) {
        if (heroRom != null) renderHero(g, mx, my);
        if (tabs.isEmpty()) {
            g.drawCenteredString(font, emptyLibraryHint(), width / 2, height / 2, 0x808890);
            return;
        }
        int cols = cardCols(), cw = cardW(), top = cardsTop();
        g.enableScissor(0, top - 4, width, height - 6);
        for (int i = 0; i < tabs.size(); i++) {
            int x = 10 + (i % cols) * (cw + 8);
            int y = top + (i / cols) * (CARD_H + 8) - (int) scrollCards;
            if (y + CARD_H < top - 4 || y > height) continue;
            renderCard(g, tabs.get(i), x, y, cw, mx, my);
        }
        g.disableScissor();
    }

    private void renderCard(GuiGraphics g, GameSystem s, int x, int y, int w, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + CARD_H && my >= cardsTop() - 4;
        long games = lib.roms.stream().filter(r -> r.system() == s).count();
        boolean hasCore = coreForSystem(s) != null;

        g.fill(x - 1, y - 1, x + w + 1, y + CARD_H + 1, hover ? 0xFF4A5468 : COL_EDGE);
        g.fill(x, y, x + w, y + CARD_H, hover ? COL_HOVER : COL_PANEL_2);

        int artH = CARD_H - 26;
        g.fill(x, y, x + w, y + artH, dim(s.color, 0.15f));
        g.fill(x, y + artH, x + w, y + artH + 2, s.color);

        g.drawString(font, font.plainSubstrByWidth(s.tab, w - 12), x + 6, y + artH + 6, 0xFFFFFF);
        long played = lib.roms.stream().filter(r -> r.system() == s)
                .mapToLong(r -> PlayStats.playtimeSec(r.id())).sum();
        String info = games + " " + plural(games)
                + (hasCore ? "" : "  §6нет ядра")
                + (played > 0 ? "  §8" + PlayStats.formatPlaytime(played) : "");
        g.drawString(font, font.plainSubstrByWidth(info, w - 12), x + 6, y + artH + 16, 0x9098A0);
    }

    private void renderHero(GuiGraphics g, int mx, int my) {
        int x = 10, y = 54, w = width - 20, h = 68;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, hover ? 0xFF4A5468 : COL_EDGE);
        g.fill(x, y, x + w, y + h, hover ? COL_SEL : COL_PANEL_2);

        int tw = 84;
        g.fill(x + 4, y + 4, x + 4 + tw, y + h - 4, dim(heroRom.system().color, 0.15f));

        int tx = x + tw + 14;
        g.drawString(font, "§8ПРОДОЛЖИТЬ", tx, y + 9, 0xFFFFFF);
        g.drawString(font, "§l" + font.plainSubstrByWidth(heroRom.displayName(), w - tw - 60),
                tx, y + 22, hover ? 0x80FF90 : 0xFFFFFF);
        String line = heroRom.system().fullName;
        long pt = PlayStats.playtimeSec(heroRom.id());
        if (pt > 0) line += " • наиграно " + PlayStats.formatPlaytime(pt);
        long st = SaveStates.saveTime(heroRom.id());
        if (st > 0) line += " §a• сохранение " + PlayStats.formatAgo(st);
        g.drawString(font, font.plainSubstrByWidth(line, w - tw - 60), tx, y + 38, 0x9098A0);
        g.drawString(font, "§8клик — продолжить • Shift+клик — новая игра", tx, y + 52, 0x707880);

        g.pose().pushPose();
        g.pose().translate(x + w - 24, y + h / 2f - 8, 0);
        g.pose().scale(2f, 2f, 1f);
        g.drawString(font, "▶", 0, 0, hover ? 0x80FF90 : 0x506050);
        g.pose().popPose();
    }

    /** Текстуры поверх кнопок — без повторного затемняющего renderBackground из super.render(). */
    private void renderCardTextures(GuiGraphics g) {
        if (heroRom != null) {
            int x = 10, y = 54, h = 68, tw = 84;
            TextureCache.Tex thumb = TextureCache.get(SaveStates.thumbFor(heroRom.id()));
            if (thumb != null) {
                g.blit(thumb.location(), x + 4, y + 4, tw, h - 8, 0f, 0f,
                        thumb.width(), thumb.height(), thumb.width(), thumb.height());
            } else {
                g.fill(x + 4, y + 4, x + 4 + tw, y + h - 4, dim(heroRom.system().color, 0.3f));
                g.drawCenteredString(font, heroRom.system().badge, x + 4 + tw / 2, y + h / 2 - 4, 0xFFFFFF);
            }
        }
        if (tabs.isEmpty()) return;
        int cols = cardCols(), cw = cardW(), top = cardsTop();
        g.enableScissor(0, top - 4, width, height - 6);
        for (int i = 0; i < tabs.size(); i++) {
            GameSystem s = tabs.get(i);
            int x = 10 + (i % cols) * (cw + 8);
            int y = top + (i / cols) * (CARD_H + 8) - (int) scrollCards;
            if (y + CARD_H < top - 4 || y > height) continue;
            int artH = CARD_H - 26;
            TextureCache.Tex art = TextureCache.get(ART_DIR.resolve(s.folder + ".png"));
            if (art != null) {
                g.blit(art.location(), x, y, cw, artH, 0f, 0f,
                        art.width(), art.height(), art.width(), art.height());
            } else {
                g.fill(x, y, x + cw, y + artH, dim(s.color, 0.22f));
                g.fill(x, y + artH - 12, x + cw, y + artH, dim(s.color, 0.35f));
                g.pose().pushPose();
                g.pose().translate(x + cw / 2f, y + artH / 2f - 8, 0);
                g.pose().scale(2f, 2f, 1f);
                g.drawCenteredString(font, s.badge, 0, 0, 0xFFFFFFFF);
                g.pose().popPose();
            }
        }
        g.disableScissor();
    }

    // ---------- вкладки и списки ----------

    private List<int[]> tabRects() { // [x, w, index] ; index -1 = «Все»
        boolean counts = tabCountsFit();
        List<int[]> out = new ArrayList<>();
        int x = 10;
        for (int i = -1; i < tabs.size(); i++) {
            int w = font.width(tabLabel(i < 0 ? null : tabs.get(i), counts)) + 14;
            out.add(new int[]{x, w, i});
            x += w + 3;
        }
        return out;
    }

    private boolean tabCountsFit() {
        int x = 10;
        for (int i = -1; i < tabs.size(); i++)
            x += font.width(tabLabel(i < 0 ? null : tabs.get(i), true)) + 17;
        return x <= width - 10;
    }

    private String tabLabel(GameSystem s, boolean counts) {
        if (s == null) return counts ? "Все (" + lib.roms.size() + ")" : "Все";
        if (!counts) return s.tab;
        long n = lib.roms.stream().filter(r -> r.system() == s).count();
        return n > 0 ? s.tab + " (" + n + ")" : s.tab;
    }

    private void renderTabs(GuiGraphics g, int mx, int my) {
        boolean counts = tabCountsFit();
        for (int[] rect : tabRects()) {
            GameSystem s = rect[2] < 0 ? null : tabs.get(rect[2]);
            boolean active = activeTab == s;
            boolean hover = mx >= rect[0] && mx < rect[0] + rect[1] && my >= 48 && my < 66;
            g.fill(rect[0], 48, rect[0] + rect[1], 66, active ? COL_SEL : hover ? COL_HOVER : COL_PANEL);
            g.fill(rect[0], 64, rect[0] + rect[1], 66,
                    active ? (s == null ? 0xFF80A0FF : s.color) : COL_EDGE);
            g.drawCenteredString(font, tabLabel(s, counts),
                    rect[0] + rect[1] / 2, 53, active ? 0xFFFFFF : hover ? 0xD0D0D0 : 0x909090);
        }
    }

    private void renderGameList(GuiGraphics g, int mx, int my, int x, int w) {
        int top = listTop(), viewH = listHeight();
        int first = (int) (scrollRoms / ITEM_H);
        for (int i = first; i < shown.size(); i++) {
            int y = top + i * ITEM_H - (int) scrollRoms;
            if (y > top + viewH - ITEM_H) break;
            RomLibrary.Rom rom = shown.get(i);
            boolean sel = rom.equals(selectedRom);
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + ITEM_H;
            boolean playable = view == View.MANUAL || coreFor(rom) != null;
            boolean fav = PlayStats.favorite(rom.id());

            g.fill(x, y, x + w, y + ITEM_H - 1,
                    sel ? COL_SEL : hover ? COL_HOVER : (i % 2 == 0 ? COL_ROW_A : COL_ROW_B));
            g.fill(x, y, x + 3, y + ITEM_H - 1, playable ? rom.system().color : 0xFF404040);

            int textX = x + 8;
            if (sel && (Util.getMillis() / 400) % 2 == 0)
                g.drawString(font, "►", textX, y + 4, 0x80FF90);
            textX += 10;

            String badge = rom.system().badge;
            int badgeW = font.width(badge) + 6;
            g.fill(textX, y + 2, textX + badgeW, y + ITEM_H - 3,
                    playable ? rom.system().color : 0xFF505050);
            g.drawString(font, badge, textX + 3, y + 4, 0xFF101010, false);

            long pt = PlayStats.playtimeSec(rom.id());
            String right = (SaveStates.hasSave(rom.id()) ? "§a●§r " : "")
                    + (pt > 0 ? "§7" + PlayStats.formatPlaytime(pt) + "§r  " : "")
                    + "§8" + formatSize(rom.size());
            int rightW = font.width(right);

            String warn = rom.misplaced() ? " ⚠" : "";
            int nameX = textX + badgeW + 6;
            int avail = x + w - nameX - rightW - 12 - (fav ? 12 : 0) - font.width(warn);
            String name = font.plainSubstrByWidth(rom.displayName(), avail);
            int color = !playable ? 0x707070 : sel ? 0x80FF90 : hover ? 0xFFFFFF : 0xC8C8C8;
            g.drawString(font, (fav ? "§e★ §r" : "") + name + warn, nameX, y + 4, color);
            g.drawString(font, right, x + w - rightW - 4, y + 4, 0xFFFFFF);
        }
        renderScrollbar(g, x + w, top, viewH, shown.size(), scrollRoms);

        if (shown.isEmpty()) {
            String hint = !searchBox.getValue().isEmpty() ? "Ничего не найдено"
                    : activeTab != null ? emptyTabHint(activeTab)
                    : emptyLibraryHint();
            g.drawCenteredString(font, hint, x + w / 2, top + 24, 0x808890);
        }
    }

    private void renderCoreList(GuiGraphics g, int mx, int my) {
        int x = 10, w = width / 2 - 18, top = listTop(), viewH = listHeight();
        int first = (int) (scrollCores / ITEM_H);
        for (int i = first; i < lib.cores.size(); i++) {
            int y = top + i * ITEM_H - (int) scrollCores;
            if (y > top + viewH - ITEM_H) break;
            RomLibrary.Core core = lib.cores.get(i);
            boolean sel = core.equals(selectedCore);
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + ITEM_H;
            g.fill(x, y, x + w, y + ITEM_H - 1,
                    sel ? COL_SEL : hover ? COL_HOVER : (i % 2 == 0 ? COL_ROW_A : COL_ROW_B));
            g.fill(x, y, x + 3, y + ITEM_H - 1, core.system().color);
            g.drawString(font, font.plainSubstrByWidth(core.displayName(), w - 12),
                    x + 8, y + 4, sel ? 0x80FF90 : hover ? 0xFFFFFF : 0xC8C8C8);
        }
        renderScrollbar(g, x + w, top, viewH, lib.cores.size(), scrollCores);
        if (lib.cores.isEmpty())
            g.drawCenteredString(font, serverLibrary
                            ? "На сервере нет ядер в config/retroconsole/cores"
                            : "Положите ядра (.dll/.so) в cores",
                    x + w / 2, top + 24, 0x808890);
    }

    private void renderInfoBar(GuiGraphics g) {
        RomLibrary.Core core = view == View.MANUAL ? selectedCore : coreFor(selectedRom);
        boolean ready = selectedRom != null && core != null;
        startBtn.active = ready;
        pickCoreBtn.visible = view == View.LIST && selectedRom != null && !lib.cores.isEmpty();

        int y = height - 52;
        g.fill(8, y, width - 8, y + 20, COL_PANEL_2);
        int rightLimit = pickCoreBtn.visible ? width - 96 : width - 14;
        if (selectedRom != null) {
            g.fill(8, y, 11, y + 20, selectedRom.system().color);
            String left = "§l" + selectedRom.displayName() + "§r  §7"
                    + selectedRom.system().fullName + " • " + formatSize(selectedRom.size());
            long pt = PlayStats.playtimeSec(selectedRom.id());
            if (pt > 0) left += " • " + PlayStats.formatPlaytime(pt);
            long st = SaveStates.saveTime(selectedRom.id());
            if (st > 0) left += " §a• сохр. " + PlayStats.formatAgo(st);
            g.drawString(font, font.plainSubstrByWidth(left, rightLimit - 230), 16, y + 6, 0xFFFFFF);
            String right = ready ? "§a" + core.displayName()
                    : view == View.MANUAL ? "§6выберите ядро слева"
                    : "§6нажмите «Ядро…» и выберите, чем запускать";
            g.drawString(font, font.plainSubstrByWidth(right, 220),
                    rightLimit - Math.min(220, font.width(right)), y + 6, 0xFFFFFF);
        } else {
            g.drawString(font, "§8Выберите игру — ↑↓, вкладки — ←→, запуск — Enter, ПКМ — избранное",
                    16, y + 6, 0xFFFFFF);
        }
    }

    // ---------- оверлеи ----------

    private void renderHelp(GuiGraphics g) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0xB0000000);
        String[] lines = {
                "§e§lСПРАВКА", "",
                "§6Игры§r — в config/retroconsole/roms",
                "Можно по папкам (nes, ps2, dreamcast…) — своя",
                "папка станет своей вкладкой и карточкой.",
                "Образы дисков распознаются автоматически.", "",
                "§6Ядра§r — *_libretro.dll (.so) в cores. Ядро",
                "подбирается само; если нет — кнопка «Ядро…»",
                "(выбор запоминается).", "",
                "§6Свои системы§r — systems.json, обложки —",
                "art/<папка>.png.", "",
                "§6Управление§r — ↑↓ выбор, ←→ вкладки, Enter —",
                "запуск, ПКМ — избранное ★, ⚠ — не своя папка.", "",
                "§8Клик или Esc — закрыть",
        };
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        int cardW = w + 32, cardH = lines.length * 12 + 24;
        int x = (width - cardW) / 2, y = (height - cardH) / 2;
        g.fill(x - 1, y - 1, x + cardW + 1, y + cardH + 1, 0xFF3C4250);
        g.fill(x, y, x + cardW, y + cardH, COL_PANEL_2);
        int ty = y + 12;
        for (String l : lines) {
            g.drawString(font, l, x + 16, ty, 0xE0E0E0);
            ty += 12;
        }
        g.pose().popPose();
    }

    /** Геометрия окна выбора ядра: [x, y, w, h, видимых строк]. */
    private int[] pickerGeom() {
        int w = 240;
        for (RomLibrary.Core c : lib.cores) w = Math.max(w, font.width(c.displayName()) + 48);
        w = Math.min(w, width - 40);
        int visible = Math.max(1, Math.min(lib.cores.size(), (height - 110) / ITEM_H));
        int h = visible * ITEM_H + 46;
        return new int[]{(width - w) / 2, (height - h) / 2, w, h, visible};
    }

    private void renderCorePicker(GuiGraphics g, int mx, int my) {
        int[] geo = pickerGeom();
        int x = geo[0], y = geo[1], w = geo[2], h = geo[3], visible = geo[4];
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0xB0000000);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF3C4250);
        g.fill(x, y, x + w, y + h, COL_PANEL_2);

        String title = "Чем запускать: §e" + (selectedRom != null ? selectedRom.displayName() : "");
        g.drawString(font, font.plainSubstrByWidth(title, w - 24), x + 12, y + 9, 0xFFFFFF);

        RomLibrary.Core current = coreFor(selectedRom);
        int listY = y + 24;
        for (int i = pickerScroll; i < lib.cores.size() && i < pickerScroll + visible; i++) {
            int ry = listY + (i - pickerScroll) * ITEM_H;
            RomLibrary.Core core = lib.cores.get(i);
            boolean isCur = core.equals(current);
            boolean hover = mx >= x + 6 && mx < x + w - 6 && my >= ry && my < ry + ITEM_H;
            g.fill(x + 6, ry, x + w - 6, ry + ITEM_H - 1,
                    hover ? COL_SEL : (i % 2 == 0 ? COL_ROW_A : COL_ROW_B));
            g.fill(x + 6, ry, x + 9, ry + ITEM_H - 1, core.system().color);
            String label = (isCur ? "✔ " : "") + core.displayName();
            g.drawString(font, font.plainSubstrByWidth(label, w - 28), x + 14, ry + 4,
                    isCur ? 0x80FF90 : hover ? 0xFFFFFF : 0xC8C8C8);
        }
        if (lib.cores.size() > visible) {
            String more = "колесо — прокрутка";
            g.drawString(font, more, x + w - font.width(more) - 10, y + h - 14, 0x707880);
        }
        g.drawString(font, "§8Esc или клик мимо — отмена", x + 12, y + h - 14, 0xFFFFFF);
        g.pose().popPose();
    }

    private void renderScrollbar(GuiGraphics g, int x, int top, int viewH, int count, double scroll) {
        int contentH = count * ITEM_H;
        if (contentH <= viewH) return;
        int barH = Math.max(12, viewH * viewH / contentH);
        int barY = top + (int) (scroll * (viewH - barH) / (contentH - viewH));
        g.fill(x + 2, top, x + 5, top + viewH, 0x30FFFFFF);
        g.fill(x + 2, barY, x + 5, barY + barH, 0xC0AAAAAA);
    }

    // ---------- ввод ----------

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (showCorePicker) {
            int[] geo = pickerGeom();
            int listY = geo[1] + 24;
            if (mx >= geo[0] + 6 && mx < geo[0] + geo[2] - 6
                    && my >= listY && my < listY + geo[4] * ITEM_H) {
                int idx = (int) ((my - listY) / ITEM_H) + pickerScroll;
                if (idx >= 0 && idx < lib.cores.size() && selectedRom != null) {
                    RomLibrary.Core chosen = lib.cores.get(idx);
                    if (selectedRom.system().custom)
                        systemOverrides.put(selectedRom.system().id, chosen.id());
                    else
                        overrides.put(selectedRom.id(), chosen.id());
                    savePrefs();
                    blip(1.6f);
                    showCorePicker = false;
                }
                return true;
            }
            showCorePicker = false;
            return true;
        }
        if (showHelp) { showHelp = false; return true; }
        if (popupSystem != null && view == View.CARDS) {
            int[] geo = popupGeom();
            int listY = geo[1] + 26;
            if (mx >= geo[0] + 6 && mx < geo[0] + geo[2] - 6
                    && my >= listY && my < listY + geo[4] * ITEM_H) {
                int idx = (int) ((my - listY) / ITEM_H) + popupScroll;
                if (idx >= 0 && idx < popupRoms.size()) {
                    RomLibrary.Rom rom = popupRoms.get(idx);
                    if (button == 1) {
                        PlayStats.toggleFavorite(rom.id());
                        popupRoms = romsOf(popupSystem);
                        refreshShown();
                        blip(1.2f);
                        return true;
                    }
                    long now = Util.getMillis();
                    if (rom.equals(lastClicked) && now - lastClickTime < 350) {
                        startEmulator(SaveStates.hasSave(rom.id()));
                        return true;
                    }
                    lastClicked = rom;
                    lastClickTime = now;
                    selectedRom = rom;
                    blip(1.6f);
                }
                return true;
            }
            if (mx >= geo[0] - 1 && mx < geo[0] + geo[2] + 1
                    && my >= geo[1] - 1 && my < geo[1] + geo[3] + 1) return true;
            popupSystem = null;
            return true;
        }
        if (super.mouseClicked(mx, my, button)) return true;

        if (view == View.CARDS) {
            if (heroRom != null && mx >= 10 && mx < width - 10 && my >= 54 && my < 122) {
                selectedRom = heroRom;
                boolean shift = (button == 0) && hasShiftDown();
                startEmulator(!shift && SaveStates.hasSave(heroRom.id()));
                return true;
            }
            int cols = cardCols(), cw = cardW(), top = cardsTop();
            if (my >= top - 4) {
                for (int i = 0; i < tabs.size(); i++) {
                    int x = 10 + (i % cols) * (cw + 8);
                    int y = top + (i / cols) * (CARD_H + 8) - (int) scrollCards;
                    if (mx >= x && mx < x + cw && my >= y && my < y + CARD_H) {
                        openPopup(tabs.get(i));
                        return true;
                    }
                }
            }
            return false;
        }

        if (view == View.LIST && my >= 48 && my < 66) {
            for (int[] rect : tabRects()) {
                if (mx >= rect[0] && mx < rect[0] + rect[1]) {
                    activeTab = rect[2] < 0 ? null : tabs.get(rect[2]);
                    blip(1.2f);
                    refreshShown();
                    return true;
                }
            }
        }

        int top = listTop(), viewH = listHeight();
        if (my >= top && my < top + viewH) {
            if (view == View.MANUAL && mx >= 10 && mx < width / 2 - 8) {
                int idx = (int) ((my - top + scrollCores) / ITEM_H);
                if (idx >= 0 && idx < lib.cores.size()) {
                    selectedCore = lib.cores.get(idx);
                    blip(1.6f);
                    return true;
                }
            }
            int gx = view == View.LIST ? 10 : width / 2 + 8;
            int gw = view == View.LIST ? width - 20 : width / 2 - 18;
            if (mx >= gx && mx < gx + gw) {
                int idx = (int) ((my - top + scrollRoms) / ITEM_H);
                if (idx >= 0 && idx < shown.size()) {
                    RomLibrary.Rom rom = shown.get(idx);
                    if (button == 1) {                       // ПКМ — избранное
                        PlayStats.toggleFavorite(rom.id());
                        blip(1.2f);
                        refreshShown();
                        return true;
                    }
                    long now = Util.getMillis();
                    if (rom.equals(lastClicked) && now - lastClickTime < 350) {
                        startEmulator();
                        return true;
                    }
                    lastClicked = rom;
                    lastClickTime = now;
                    selectedRom = rom;
                    blip(1.6f);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double hy) {
        if (showCorePicker) {
            int visible = pickerGeom()[4];
            pickerScroll = Math.max(0, Math.min(pickerScroll - (int) Math.signum(hy),
                    Math.max(0, lib.cores.size() - visible)));
            return true;
        }
        if (popupSystem != null && view == View.CARDS) {
            int visible = popupGeom()[4];
            popupScroll = Math.max(0, Math.min(popupScroll - (int) Math.signum(hy),
                    Math.max(0, popupRoms.size() - visible)));
            return true;
        }
        if (view == View.CARDS) {
            int rows = (tabs.size() + cardCols() - 1) / cardCols();
            int contentH = rows * (CARD_H + 8);
            int viewH = height - cardsTop() - 6;
            targetCards = Math.max(0, Math.min(targetCards - hy * 24, Math.max(0, contentH - viewH)));
            return true;
        }
        int viewH = listHeight();
        if (view == View.MANUAL && mx < width / 2.0) {
            targetCores = clamp(targetCores - hy * ITEM_H * 2, lib.cores.size(), viewH);
        } else {
            targetRoms = clamp(targetRoms - hy * ITEM_H * 2, shown.size(), viewH);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (showCorePicker && key == GLFW.GLFW_KEY_ESCAPE) { showCorePicker = false; return true; }
        if (showHelp && (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER)) {
            showHelp = false;
            return true;
        }
        if (popupSystem != null && view == View.CARDS && !showCorePicker) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { popupSystem = null; return true; }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                boolean shift = hasShiftDown();
                boolean loadAuto = !shift && selectedRom != null && SaveStates.hasSave(selectedRom.id());
                startEmulator(loadAuto);
                return true;
            }
            if (!popupRoms.isEmpty() && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
                int idx = selectedRom == null ? -1 : popupRoms.indexOf(selectedRom);
                idx = key == GLFW.GLFW_KEY_DOWN ? Math.min(popupRoms.size() - 1, idx + 1) : Math.max(0, idx - 1);
                selectedRom = popupRoms.get(idx);
                int visible = popupGeom()[4];
                if (idx < popupScroll) popupScroll = idx;
                else if (idx >= popupScroll + visible) popupScroll = idx - visible + 1;
                blip(1.6f);
                return true;
            }
            return true;
        }
        if (!showCorePicker && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            if (view == View.CARDS) {
                if (heroRom != null) {
                    selectedRom = heroRom;
                    boolean shift = hasShiftDown();
                    startEmulator(!shift && SaveStates.hasSave(heroRom.id()));
                }
            } else {
                startEmulator(false);
            }
            return true;
        }
        if (!showCorePicker && view != View.CARDS && !searchBox.isFocused()) {
            if (view == View.LIST && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
                int idx = activeTab == null ? 0 : tabs.indexOf(activeTab) + 1;
                idx += key == GLFW.GLFW_KEY_RIGHT ? 1 : -1;
                idx = Math.floorMod(idx, tabs.size() + 1);
                activeTab = idx == 0 ? null : tabs.get(idx - 1);
                blip(1.2f);
                refreshShown();
                return true;
            }
            if (!shown.isEmpty() && (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP)) {
                int idx = selectedRom == null ? -1 : shown.indexOf(selectedRom);
                idx = key == GLFW.GLFW_KEY_DOWN ? Math.min(shown.size() - 1, idx + 1) : Math.max(0, idx - 1);
                selectedRom = shown.get(idx);
                blip(1.6f);
                int topPix = idx * ITEM_H;
                if (topPix < targetRoms) targetRoms = topPix;
                else if (topPix + ITEM_H > targetRoms + listHeight())
                    targetRoms = topPix + ITEM_H - listHeight();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ---------- prefs / утилиты ----------

    private void blip(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT, pitch));
    }

    private static double approach(double current, double target) {
        double next = current + (target - current) * 0.5;
        return Math.abs(target - next) < 0.5 ? target : next;
    }

    private void loadPrefs() {
        Properties p = new Properties();
        RomLibrary.loadProps(p, ClientPlayerData.uiFile());
        try { view = View.valueOf(p.getProperty("view", "CARDS")); } catch (Exception ignored) {}
        modeBtn.setMessage(Component.literal(viewLabel()));
        String rom = p.getProperty("rom");
        if (rom != null) lib.roms.stream().filter(r -> r.id().equals(rom)).findFirst()
                .ifPresent(r -> selectedRom = r);
        String core = p.getProperty("core");
        if (core != null) lib.cores.stream().filter(c -> c.id().equals(core)).findFirst()
                .ifPresent(c -> selectedCore = c);
        overrides.clear();
        systemOverrides.clear();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith("override."))
                overrides.put(key.substring("override.".length()), p.getProperty(key));
            if (key.startsWith("syscore."))
                systemOverrides.put(key.substring("syscore.".length()), p.getProperty(key));
        }
    }

    private void savePrefs() {
        Properties p = new Properties();
        p.setProperty("view", view.name());
        if (selectedRom != null) p.setProperty("rom", selectedRom.id());
        RomLibrary.Core core = view == View.MANUAL ? selectedCore : coreFor(selectedRom);
        if (core != null) p.setProperty("core", core.id());
        for (Map.Entry<String, String> e : overrides.entrySet())
            p.setProperty("override." + e.getKey(), e.getValue());
        for (Map.Entry<String, String> e : systemOverrides.entrySet())
            p.setProperty("syscore." + e.getKey(), e.getValue());
        RomLibrary.saveProps(p, ClientPlayerData.uiFile(), "retroconsole ui prefs");
    }

    private double clamp(double v, int count, int viewH) {
        return Math.max(0, Math.min(v, Math.max(0, count * ITEM_H - viewH)));
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    private String emptyLibraryHint() {
        return serverLibrary
                ? "На сервере нет ROM/ядер — положите файлы в config/retroconsole/ на сервере"
                : "Закиньте ядра в cores и игры в roms — кнопка «Папка»";
    }

    private String emptyTabHint(GameSystem tab) {
        return serverLibrary
                ? "На сервере нет игр в roms/" + tab.folder
                : "Пусто. Закиньте игры в roms/" + tab.folder + " — кнопка «Папка»";
    }

    private static String plural(long n) {
        long m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "игра";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "игры";
        return "игр";
    }

    private static int dim(int color, float f) {
        int r = (int) (((color >> 16) & 0xFF) * f);
        int gr = (int) (((color >> 8) & 0xFF) * f);
        int b = (int) ((color & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }
}