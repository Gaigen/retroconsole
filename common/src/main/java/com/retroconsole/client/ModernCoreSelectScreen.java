package com.retroconsole.client;

import com.retroconsole.client.library.PlayStats;
import com.retroconsole.client.library.SaveStates;
import com.retroconsole.library.GameSystem;
import com.retroconsole.library.RomLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Opt-in library presentation. The superclass remains the classic screen and
 * owns catalog loading, selection, favorites, core overrides and launch packets.
 * No save format, emulator lifecycle or server behavior is implemented here.
 */
public final class ModernCoreSelectScreen extends CoreSelectScreen {
    private static final int BG = 0xFF101318, SURFACE = 0xFF181D25;
    private static final int BORDER = 0xFF4D5769, ACCENT = 0xFFB8C3FF;
    private static final int TEXT = 0xFFE8EAF0, MUTED = 0xFFADB7C8;
    private LibraryButton freshBtn;
    private final LibraryButton[] modes = new LibraryButton[3];

    public ModernCoreSelectScreen(BlockPos pos) { super(pos); }

    @Override protected Button.Builder button(Component label, Button.OnPress action) {
        return new LibraryButton.Builder(label, action);
    }

    @Override protected void init() {
        super.init();
        for (int i = 0; i < modes.length; i++) {
            View target = View.values()[i];
            String key = switch (target) {
                case CARDS -> "gui.view.cards";
                case LIST -> "gui.view.list";
                case MANUAL -> "gui.view.manual";
            };
            modes[i] = addRenderableWidget(new LibraryButton(10 + i * 62, 28, 58, 20,
                    ModTexts.c(key), b -> {
                        view = target;
                        popupSystem = null;
                        refreshShown();
                        ensureActiveTabVisible();
                        savePrefs();
                        blip(1.2f);
                    }).selected(() -> view == target));
        }
        freshBtn = addRenderableWidget(new LibraryButton(0, 0, 148, 20,
                ModTexts.c("modern.new"), b -> startEmulator(false)));
        ((LibraryButton) startBtn).primary();
        pickCoreBtn.setHeight(20);
        ((LibraryButton) settingsBtn).settingsIcon();
        settingsBtn.setMessage(ModTexts.c("gui.settings.tooltip"));
        freshBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(ModTexts.c("modern.new_hint")));
    }

    private int headerH() { return width < 420 ? 78 : 54; }
    private boolean details() { return width >= 580 && height >= 280; }
    private int detailW() { return 164; }
    private int detailX() { return width - 10 - detailW(); }
    @Override protected int tabTop() { return headerH() + 4; }
    @Override protected int heroY() { return headerH() + 10; }
    @Override protected int listTop() { return headerH() + (view == View.LIST ? 24 : 8); }
    @Override protected int listHeight() {
        return Math.max(ITEM_H, (height - listTop() - (details()?12:78)) / ITEM_H * ITEM_H);
    }
    @Override protected int coreW() { return details() ? Math.min(160, width / 4) : super.coreW(); }
    @Override protected int gameX() { return view == View.MANUAL ? 10 + coreW() + 12 : 10; }
    @Override protected int gameW() {
        return (details() ? detailX() - 12 : width - 10) - gameX();
    }
    @Override protected int cardCols() { return Math.max(1, Math.min(4, (width - 20) / 190)); }
    @Override protected int textWidth(String value) { return UiTypography.width(font, value); }
    @Override protected String trimText(String value, int max) { return UiTypography.trim(font, value, max); }
    @Override protected void drawText(GuiGraphicsExtractor g, String value, int x, int y, int color) {
        drawText(g, value, x, y, color, false);
    }
    @Override protected void drawText(GuiGraphicsExtractor g, String value, int x, int y, int color, boolean shadow) {
        g.text(font, UiTypography.text(value), x, y, color == 0xFF80FF90 ? ACCENT : color, shadow);
    }
    @Override protected void centerText(GuiGraphicsExtractor g, String value, int x, int y, int color) {
        var text = UiTypography.text(value);
        g.text(font, text, x - font.width(text) / 2, y, color, false);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, BG);
    }

    @Override protected void renderHeader(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, headerH() - 1, BG);
        g.fill(0, headerH() - 1, width, headerH(), BORDER);
        drawText(g, "RETROCONSOLE", 10, 9, TEXT);
        modeBtn.visible = false;
        backBtn.visible = false;
        folderBtn.setPosition(width - 136, 5);
        folderBtn.setWidth(52);
        settingsBtn.setPosition(width - 80, 5);
        helpBtn.setPosition(width - 56, 5);
        appearanceBtn.setPosition(width - 32, 5);
        appearanceBtn.setHeight(18);
        searchBox.setPosition(width < 420 ? 12 : 208, width < 420 ? 57 : 31);
        searchBox.setWidth(Math.max(60, width < 420 ? width - 24 : width - 220));
        // Cards are not filtered by the ROM search in the original controller.
        searchBox.visible = view != View.CARDS;
        if (freshBtn != null) freshBtn.visible = view != View.CARDS && selectedRom != null;
    }

    @Override protected void renderStats(GuiGraphicsExtractor g, String stats) {
        // Counts use the second row, not the logo or the settings hitboxes.
        if (width >= 420) drawText(g, trimText(stats, width - 220), 208, 33, MUTED);
    }

    private void contain(GuiGraphicsExtractor g, TextureCache.Tex tex, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF090C10);
        double scale = Math.min((double) w / tex.width(), (double) h / tex.height());
        int dw = Math.max(1, (int) Math.round(tex.width() * scale));
        int dh = Math.max(1, (int) Math.round(tex.height() * scale));
        g.blit(RenderPipelines.GUI_TEXTURED, tex.location(), x + (w - dw) / 2, y + (h - dh) / 2,
                0f, 0f, dw, dh, tex.width(), tex.height(), tex.width(), tex.height());
    }

    @Override protected void renderGameList(GuiGraphicsExtractor g, int mx, int my, int x, int w) {
        if (!shown.isEmpty()) {
            g.enableScissor(x,listTop(),x+w+6,listTop()+listHeight());
            super.renderGameList(g, mx, my, x, w);
            g.disableScissor();return;
        }
        String hint = !searchBox.getValue().isEmpty() ? ModTexts.s("gui.no_results")
                : activeTab != null ? emptyTabHint(activeTab) : emptyLibraryHint();
        int y = listTop() + 24;
        g.enableScissor(x, listTop(), x + w, listTop() + listHeight());
        for (var line : font.split(UiTypography.text(hint), Math.max(20, w - 24))) {
            g.centeredText(font, line, x + w / 2, y, MUTED);
            y += 12;
        }
        g.disableScissor();
    }

    @Override protected void renderCoreList(GuiGraphicsExtractor g,int mx,int my) {
        g.enableScissor(8,listTop(),10+coreW()+6,listTop()+listHeight());
        super.renderCoreList(g,mx,my);g.disableScissor();
    }

    @Override protected void renderInfoBar(GuiGraphicsExtractor g) {
        if (!details()) {
            RomLibrary.Core core=view==View.MANUAL?selectedCore:coreFor(selectedRom);
            boolean ready=selectedRom!=null&&core!=null;
            int y=height-70;
            g.fill(8,y,width-8,y+32,SURFACE);
            pickCoreBtn.visible=view==View.LIST&&selectedRom!=null&&!lib.cores.isEmpty();
            int bw=(width-20-(pickCoreBtn.visible?8:4))/(pickCoreBtn.visible?3:2);
            int coreW=pickCoreBtn.visible?width-20-2*bw-8:0;
            startBtn.setPosition(10,height-26);startBtn.setWidth(bw);startBtn.active=ready;
            freshBtn.setPosition(14+bw,height-26);freshBtn.setWidth(bw);freshBtn.active=ready;
            freshBtn.visible=selectedRom!=null;
            pickCoreBtn.setPosition(width-10-coreW,height-26);pickCoreBtn.setWidth(Math.max(1,coreW));
            boolean state=selectedRom!=null&&!serverLibrary&&SaveStates.hasSave(selectedRom.id());
            startBtn.setMessage(ModTexts.c(state?"modern.continue":"gui.start"));
            if(selectedRom!=null) {
                g.fill(8,y,10,y+32,selectedRom.system().color);
                drawText(g,trimText(selectedRom.displayName(),width-32),16,y+4,TEXT);
                String meta=ModTexts.fullName(selectedRom.system())+" • "+formatSize(selectedRom.size())+
                    (core==null?"":" • "+core.displayName());
                drawText(g,trimText(meta,width-32),16,y+18,MUTED);
            } else drawText(g,ModTexts.s("modern.select"),16,y+10,MUTED);
            return;
        }
        int x = detailX(), y = listTop() - 3, w = detailW(), bottom = listTop() + listHeight() + 3;
        g.fill(x - 1, y, x + w + 1, bottom, BORDER);
        g.fill(x, y + 1, x + w, bottom - 1, SURFACE);
        RomLibrary.Core core = view == View.MANUAL ? selectedCore : coreFor(selectedRom);
        boolean ready = selectedRom != null && core != null;
        startBtn.active = ready;
        startBtn.setPosition(x + 8, bottom - 70);
        startBtn.setWidth(w - 16);
        freshBtn.setPosition(x + 8, bottom - 46);
        freshBtn.setWidth(w - 16);
        freshBtn.active = ready;
        freshBtn.visible = selectedRom != null;
        pickCoreBtn.visible = view == View.LIST && selectedRom != null && !lib.cores.isEmpty();
        pickCoreBtn.setPosition(x + 8, bottom - 22);
        pickCoreBtn.setWidth(w - 16);
        if (selectedRom == null) {
            centerText(g, ModTexts.s("modern.select"), x + w / 2, y + 28, MUTED);
            return;
        }
        boolean state = !serverLibrary && SaveStates.hasSave(selectedRom.id());
        startBtn.setMessage(ModTexts.c(state ? "modern.continue" : "gui.start"));
        int contentBottom = bottom - 76;
        g.enableScissor(x + 4, y + 4, x + w - 4, contentBottom);
        int ty = y + 10;
        TextureCache.Tex thumb = serverLibrary ? null : TextureCache.get(SaveStates.thumbFor(selectedRom.id()));
        if (thumb != null && contentBottom - ty > 126) {
            contain(g, thumb, x + 8, ty, w - 16, 80);
            ty += 90;
        }
        drawText(g, trimText(ModTexts.fullName(selectedRom.system()), w - 16), x + 8, ty, ACCENT);
        ty += 16;
        for (var line : font.split(UiTypography.text(selectedRom.displayName()), w - 16)) {
            g.text(font, line, x + 8, ty, TEXT, false);
            ty += 11;
        }
        ty += 12;
        drawText(g, ModTexts.s("modern.time"), x + 8, ty, MUTED);
        drawText(g, (PlayStats.playtimeSec(selectedRom.id()) > 0 ? PlayStats.formatPlaytime(PlayStats.playtimeSec(selectedRom.id())) : "—"), x + 8, ty + 12, TEXT);
        ty += 30;
        drawText(g, ModTexts.s("modern.size") + ": " + formatSize(selectedRom.size()), x + 8, ty, MUTED);
        ty += 20;
        drawText(g, ModTexts.s("modern.core"), x + 8, ty, MUTED);
        drawText(g, trimText(core == null ? "—" : core.displayName(), w - 16), x + 8, ty + 12, TEXT);
        ty += 30;
        if (state) drawText(g, ModTexts.s("modern.save"), x + 8, ty, 0xFFA8D9B5);
        g.disableScissor();
    }

    @Override protected void startEmulator() {
        // Same path as the original popup/hero: loadAuto is distinct from starting without state.
        startEmulator(!serverLibrary && selectedRom != null && SaveStates.hasSave(selectedRom.id()));
    }

    private record HelpSection(String title,String text) {}
    private HelpSection[] helpSections() {
        return new HelpSection[]{
            new HelpSection(ModTexts.s("modern.help.library"),ModTexts.s(serverLibrary?"modern.help.server":"modern.help.local")),
            new HelpSection(ModTexts.s("modern.help.files"),ModTexts.s("modern.help.files_body")),
            new HelpSection(ModTexts.s("modern.help.play"),ModTexts.s("play.menu_hint")+"\n\n"+ModTexts.s("play.capture_hint")),
            new HelpSection(ModTexts.s("modern.help.states"),ModTexts.s("modern.help.states_body"))};
    }
    private int helpSection(GuiGraphicsExtractor g,HelpSection section,int x,int y,int w) {
        drawText(g,section.title(),x,y,ACCENT);y+=18;
        for(String paragraph:section.text().split("\n\n")) {
            for(var line:font.split(UiTypography.text(paragraph),w)) {g.text(font,line,x,y,TEXT,false);y+=13;}
            y+=6;
        }
        return y+10;
    }
    @Override protected void renderHelp(GuiGraphicsExtractor g) {
        g.nextStratum();g.fill(0,0,width,height,0xC0000000);
        int w=Math.min(width-24,680),h=Math.min(height-24,364),x=(width-w)/2,y=(height-h)/2;
        g.fill(x-1,y-1,x+w+1,y+h+1,BORDER);g.fill(x,y,x+w,y+h,SURFACE);
        drawText(g,ModTexts.s("modern.help"),x+14,y+12,TEXT);
        g.fill(x+14,y+30,x+w-14,y+31,BORDER);
        int columns=w>=500?2:1,gap=28,cw=(w-28-(columns-1)*gap)/columns;
        int top=y+42,bottom=y+h-30;
        g.enableScissor(x+12,top,x+w-12,bottom);
        HelpSection[] sections=helpSections();int total=0;
        for(int col=0;col<columns;col++) {
            int ty=top-helpScroll;
            int first=columns==2?col*2:0,last=columns==2?first+2:sections.length;
            for(int i=first;i<last;i++)ty=helpSection(g,sections[i],x+14+col*(cw+gap),ty,cw);
            total=Math.max(total,ty+helpScroll-top);
        }
        g.disableScissor();helpMaxScroll=Math.max(0,total-(bottom-top));helpScroll=Math.min(helpScroll,helpMaxScroll);
        if(helpMaxScroll>0) {
            int track=bottom-top,thumb=Math.max(14,track*track/(track+helpMaxScroll));
            int sy=top+(track-thumb)*helpScroll/helpMaxScroll;
            g.fill(x+w-6,top,x+w-4,bottom,BORDER);g.fill(x+w-6,sy,x+w-4,sy+thumb,ACCENT);
        }
        g.fill(x+14,y+h-23,x+w-14,y+h-22,BORDER);
        drawText(g,ModTexts.s(helpMaxScroll>0?"modern.help.scroll":"modern.help.close"),x+14,y+h-16,MUTED);
    }
    @Override protected int[] popupGeom() {
        int w = Math.min(width - 24, 680);
        int visible = Math.max(6, Math.min(Math.max(popupRoms.size(), 1), Math.max(6, (height - 104) / ITEM_H)));
        int h = Math.min(height - 32, Math.max(276, visible * ITEM_H + 66));
        visible = Math.max(1, (h - 66) / ITEM_H);
        return new int[]{(width - w) / 2, (height - h) / 2, w, h, visible};
    }

    @Override protected void renderGamePopup(GuiGraphicsExtractor g, int mx, int my) {
        int[] r = popupGeom();
        int x = r[0], y = r[1], w = r[2], h = r[3], visible = r[4], split = popupSplit(x,w);
        g.nextStratum();
        g.fill(0, 0, width, height, 0xBF000000);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, SURFACE);
        drawText(g, trimText(ModTexts.fullName(popupSystem), w - 52), x + 12, y + 9, TEXT);
        modalButton(g, x + w - 26, y + 4, 22, 18, "×", false, true, mx, my);
        int listY = y + 26;
        g.fill(split, listY, split + 1, y + h - 24, BORDER);
        for (int i = popupScroll; i < popupRoms.size() && i < popupScroll + visible; i++) {
            int ry = listY + (i - popupScroll) * ITEM_H;
            var rom = popupRoms.get(i);
            boolean selected = rom.equals(selectedRom);
            boolean hover = mx >= x + 6 && mx < split - 8 && my >= ry && my < ry + ITEM_H;
            g.fill(x + 6, ry, split - 8, ry + ITEM_H - 1, selected ? COL_SEL : hover ? COL_HOVER : SURFACE);
            if (selected) g.fill(x + 6, ry, x + 8, ry + ITEM_H - 1, ACCENT);
            String right = PlayStats.formatPlaytime(PlayStats.playtimeSec(rom.id()));
            int rightW = textWidth(right);
            drawText(g, trimText((PlayStats.favorite(rom.id()) ? "★ " : "") + rom.displayName(),
                    split - x - 40 - rightW), x + 14, ry + 4, selected ? TEXT : MUTED);
            drawText(g, right, split - 18 - rightW, ry + 4, MUTED);
        }
        if (popupRoms.isEmpty()) drawText(g, trimText(emptyTabHint(popupSystem), split - x - 24), x + 12, listY + 18, MUTED);
        int dx = split + 10, dw = x+w-10-dx;
        if (selectedRom != null) {
            g.enableScissor(dx, listY + 4, dx + dw, y + h - 78);
            int ty = listY + 4, count=0;
            for (var line : font.split(UiTypography.text(selectedRom.displayName()), dw)) {
                if(count++>=2)break;
                g.text(font,line,dx,ty,TEXT,false);ty+=11;
            }
            ty+=6;
            int previewH=Math.min(96,y+h-78-ty-46);
            if(previewH>=24) {
                drawText(g,ModTexts.s("modern.preview"),dx,ty,MUTED);ty+=12;
                // Existing client thumbnail is the last viewed frame, not an authoritative server save screenshot.
                TextureCache.Tex thumb=TextureCache.get(SaveStates.thumbFor(selectedRom.id()));
                if(thumb!=null)contain(g,thumb,dx,ty,dw,previewH);
                else {
                    g.fill(dx,ty,dx+dw,ty+previewH,0xFF090C10);
                    centerText(g,ModTexts.s("modern.no_preview"),dx+dw/2,ty+(previewH-10)/2,MUTED);
                }
                ty+=previewH+8;
            }
            drawText(g,formatSize(selectedRom.size()),dx,ty,MUTED);
            var core=coreFor(selectedRom);
            drawText(g,trimText(core==null?ModTexts.s("modern.core")+": —":core.displayName(),dw),dx,ty+14,MUTED);
            g.disableScissor();
            boolean state = !serverLibrary && SaveStates.hasSave(selectedRom.id());
            modalButton(g, dx, y + h - 72, dw, 20,
                    ModTexts.s(core == null ? "gui.core_picker" : state ? "modern.continue" : "gui.start"), true, true, mx, my);
            modalButton(g, dx, y + h - 48, dw, 20, ModTexts.s("modern.new"), false, core != null, mx, my);
        }
        drawText(g, trimText(ModTexts.s("popup.close"), w - 24), x + 12, y + h - 15, MUTED);
    }

    private int popupSplit(int x,int w) {return x+w-Math.clamp(w/3,128,184)-20;}

    private void modalButton(GuiGraphicsExtractor g, int x, int y, int w, int h, String label,
                             boolean primary, boolean active, int mx, int my) {
        boolean hover = active && mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? ACCENT : BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, primary ? ACCENT : hover ? COL_HOVER : SURFACE);
        centerText(g, trimText(label, w - 8), x + w / 2, y + 6,
                !active ? 0xFF8991A0 : primary ? 0xFF171C2B : TEXT);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!showCorePicker && !showHelp && popupSystem != null && view == View.CARDS) {
            int[] r = popupGeom();
            double mx = event.x(), my = event.y();
            int x = r[0], y = r[1], w = r[2], h = r[3], split = popupSplit(x,w);
            if (mx >= x + w - 26 && mx < x + w - 4 && my >= y + 4 && my < y + 22) {
                popupSystem = null;
                return true;
            }
            if (mx >= split && mx < x + w && my >= y + 26 && my < y + h - 24) {
                if (event.button() == 0 && selectedRom != null && mx >= split + 10 && mx < x + w - 10) {
                    if (my >= y + h - 72 && my < y + h - 52) {
                        if (coreFor(selectedRom) == null) { showCorePicker = true; pickerScroll = 0; }
                        else startEmulator();
                    } else if (my >= y + h - 48 && my < y + h - 28 && coreFor(selectedRom) != null) {
                        startEmulator(false);
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if(showHelp&&(event.key()==GLFW.GLFW_KEY_PAGE_DOWN||event.key()==GLFW.GLFW_KEY_PAGE_UP)) {
            helpScroll=Math.clamp(helpScroll+(event.key()==GLFW.GLFW_KEY_PAGE_DOWN?96:-96),0,helpMaxScroll);return true;
        }
        if (!showHelp && !showCorePicker && popupSystem == null && view != View.CARDS
                && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            if(getFocused() instanceof Button button && button.visible && button.active) button.onPress(event);
            else if (minecraft.hasShiftDown()) startEmulator(false);
            else startEmulator();
            return true;
        }
        return super.keyPressed(event);
    }

    private int helpScroll, helpMaxScroll;
    @Override public boolean mouseScrolled(double mx, double my, double hx, double hy) {
        if (showHelp) {
            helpScroll = Math.max(0, Math.min(helpMaxScroll, helpScroll - (int) (hy * 24)));
            return true;
        }
        if (details() && popupSystem == null && !showCorePicker && view != View.CARDS && mx >= detailX()) return true;
        return super.mouseScrolled(mx, my, hx, hy);
    }
}
