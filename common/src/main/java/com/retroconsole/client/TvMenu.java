package com.retroconsole.client;

import com.retroconsole.client.bezel.BezelSettingsScreen;
import com.retroconsole.client.library.SoundPrefs;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Embedded menu, never installed as Minecraft.screen. No VIEW or emulator lifecycle here. */
final class TvMenu extends Screen {
    enum Page { MAIN, SAVES, CONTROLS, AV, BEZEL }
    private final TvScreen owner;
    private Page page=Page.MAIN;
    private boolean open;
    private int scroll, maxScroll;
    private int cursor;
    private BezelSettingsScreen bezel;
    private final List<AbstractWidget> body=new ArrayList<>(), chrome=new ArrayList<>();
    private record Line(String text,int y,int color) {}
    private final List<Line> lines=new ArrayList<>();
    private record BindingCell(String action,String value,int x,int y,int w,int h,int labelW) {}
    private final List<BindingCell> bindings=new ArrayList<>();
    private int headerTextX() {return x()+12+(page==Page.MAIN?0:28);}
    TvMenu(TvScreen owner) { super(ModTexts.c("play.menu"));this.owner=owner; }
    /** Embedded UI, not Minecraft.screen: do not publish global ScreenEvent.Init events.
     * In NeoForge Screen.rebuildWidgets fires those events; GUI integrations may cancel
     * the init of a non-current screen, leaving an open panel with no buttons on resize. */
    @Override protected void rebuildWidgets() {
        clearWidgets();clearFocus();setDragging(false);init();setInitialFocus();
    }
    @Override public void resize(net.minecraft.client.Minecraft mc,int w,int h) {
        minecraft=mc;font=mc.font;width=w;height=h;rebuildWidgets();
    }

    boolean isOpen() { return open; }
    int panelWidth() { return page==Page.CONTROLS?Math.min(264,Math.max(216,width*38/100)):Math.min(240,Math.max(184,width*38/100)); }
    int videoWidth() { return open && page!=Page.BEZEL ? Math.max(1,width-panelWidth()) : width; }
    private int x() {return width-panelWidth();}
    private int bottom() {return page==Page.MAIN?height-52:height-10;}
    private int buttonH() {return height<300?18:20;}
    void open(Page target) {
        owner.suspendGameInput();open=true;page=target;scroll=0;rebuildWidgets();
    }
    void closeMenu() {open=false;clearWidgets();body.clear();chrome.clear();setFocused(null);}
    private void page(Page target) {page=target;scroll=0;rebuildWidgets();}
    @Override protected void init() {
        body.clear();chrome.clear();lines.clear();bindings.clear();cursor=46-scroll;
        if(!open)return;
        if(page==Page.BEZEL) {
            bezel=new BezelSettingsScreen(owner) { @Override public void onClose(){page(Page.AV);} };
            bezel.initEmbedded(minecraft,width,height);return;
        }
        int left=x()+12, w=panelWidth()-24;
        chrome.add(addRenderableWidget(new LibraryButton(width-34,12,22,22,Component.literal("×"),b->closeMenu())));
        if(page!=Page.MAIN) chrome.add(addRenderableWidget(new LibraryButton(left,12,22,22,Component.literal("<"),b->page(Page.MAIN))));
        switch(page) {
            case MAIN -> {
                button(ModTexts.c("play.resume"),()->closeMenu(),true);
                volume();
                button(ModTexts.c("play.saves"),()->page(Page.SAVES),false);
                button(ModTexts.c("play.controls"),()->page(Page.CONTROLS),false);
                button(ModTexts.c("play.av"),()->page(Page.AV),false);
                var p2=new LibraryButton(left,cursor,w,buttonH(),p2Label(),b->{owner.requestP2(!owner.p2Requested());b.setMessage(p2Label());});
                body.add(addRenderableWidget(p2));cursor+=buttonH()+3;
                chrome.add(addRenderableWidget(new LibraryButton(left,height-46,w,18,ModTexts.c("play.library"),b->owner.returnToLibrary())));
                chrome.add(addRenderableWidget(new LibraryButton(left,height-24,w,18,ModTexts.c("tv.power_off"),b->owner.powerOffFromMenu())));
            }
            case SAVES -> {
                note(ModTexts.s("play.save_slot"));
                button(ModTexts.c("play.save"),()->owner.requestState(true),false);
                button(ModTexts.c("play.load"),()->owner.requestState(false),false);
                note(ModTexts.s("play.save_notice"));
            }
            case CONTROLS -> controls();
            case AV -> {
                button(ModTexts.c("tv.bezel"),()->page(Page.BEZEL),false);
                note(ModTexts.s("play.fit_notice"));volume();
            }
            default -> {}
        }
        maxScroll=Math.max(0,cursor+scroll-bottom());
        if(scroll>maxScroll){scroll=maxScroll;rebuildWidgets();return;}
        for(var widget:body)widget.visible=widget.getY()>=42&&widget.getY()+widget.getHeight()<=bottom();
    }
    private Component p2Label() {return ModTexts.c(owner.p2Requested()?"play.p2_leave":"play.p2_join");}
    private String buttonName(int id) {
        return switch(id) {
            case 0->"B";case 1->"Y";case 2->"Select";case 3->"Start";case 4->"D-Pad ↑";case 5->"D-Pad ↓";
            case 6->"D-Pad ←";case 7->"D-Pad →";case 8->"A";case 9->"X";case 10->"L1";case 11->"R1";
            case 12->"L2";case 13->"R2";case 14->"L3";case 15->"R3";default->Integer.toString(id);
        };
    }
    private String keyName(KeyMapping key) {return key.getTranslatedKeyMessage().getString();}
    private void controls() {
        lines.add(new Line(ModTexts.s("play.bindings_compact"),cursor,0xFFADB7C8));cursor+=14;
        int row=owner.touchProfile()&&height<280?11:12;
        int gap=12, col=(panelWidth()-24-gap)/2, left=x()+12;
        int labelW=0;
        for(var bind:RetroInputBindings.BUTTONS)labelW=Math.max(labelW,UiTypography.bindingWidth(font,buttonName(bind.retroId()).replace("D-Pad ","")));
        labelW=Math.min(labelW,36);
        for(int i=0;i<RetroInputBindings.BUTTONS.length;i++) {
            var bind=RetroInputBindings.BUTTONS[i];
            String action=buttonName(bind.retroId()).replace("D-Pad ", "");
            bindings.add(new BindingCell(action,keyName(bind.key()),left+(i%2)*(col+gap),cursor+(i/2)*row,col,row,labelW));
        }
        cursor+=8*row+2;
        stickRow(RetroInputBindings.LEFT_STICK,"L ↑↓←→",row);
        stickRow(RetroInputBindings.RIGHT_STICK,"R ↑↓←→",row);
        if(owner.touchProfile()) {
            cursor+=2;
            String[] actions={ModTexts.s("play.bind.mic"),ModTexts.s("play.bind.layout"),ModTexts.s("play.bind.lid"),ModTexts.s("play.bind.touch"),ModTexts.s("play.bind.stylus")};
            KeyMapping[] keys={ModKeys.BTN_MIC,ModKeys.BTN_LAYOUT,ModKeys.BTN_LID,ModKeys.BTN_CURSOR_TOUCH,ModKeys.VIRTUAL_STYLUS};
            int dsLabelW=0;for(String action:actions)dsLabelW=Math.max(dsLabelW,UiTypography.bindingWidth(font,action));
            dsLabelW=Math.min(dsLabelW,40);
            for(int i=0;i<keys.length;i++)bindings.add(new BindingCell(actions[i],keyName(keys[i]),left+(i%2)*(col+gap),cursor+(i/2)*row,col,row,dsLabelW));
            cursor+=3*row;
        }
        cursor+=3;
        lines.add(new Line(ModTexts.s("play.controls_keys"),cursor,0xFFADB7C8));cursor+=12;
        if(owner.keyboardProfile()) {
            lines.add(new Line(ModTexts.s("play.capture_compact"),cursor,0xFFADB7C8));cursor+=12;
        }
    }

    private void stickRow(RetroInputBindings.StickBind stick,String action,int row) {
        String keys=keyName(stick.up())+" / "+keyName(stick.down())+" / "+keyName(stick.left())+" / "+keyName(stick.right());
        bindings.add(new BindingCell(action,keys,x()+12,cursor,panelWidth()-24,row,44));cursor+=row;
    }
    private String fitBinding(String value,int width) {
        return UiTypography.bindingWidth(font,value)<=width?value:UiTypography.bindingTrim(font,value,Math.max(0,width-UiTypography.bindingWidth(font,"…")))+"…";
    }
    private void renderBindings(GuiGraphics g,int mx,int my) {
        for(var cell:bindings) {
            if(cell.y()+cell.h()<=42||cell.y()>=bottom())continue;
            int labelW=cell.labelW();
            int valueW=cell.w()-labelW-10;
            String action=fitBinding(cell.action(),labelW), value=fitBinding(cell.value(),valueW);
            g.fill(cell.x(),cell.y(),cell.x()+cell.w(),cell.y()+cell.h()-1,0xFF1D2430);
            g.fill(cell.x()+labelW+5,cell.y()+1,cell.x()+labelW+6,cell.y()+cell.h()-2,0xFF353F50);
            int textY=cell.y()+Math.max(1,(cell.h()-8)/2);
            g.enableScissor(cell.x()+2,Math.max(42,cell.y()+1),cell.x()+cell.w()-2,Math.min(bottom(),cell.y()+cell.h()-1));
            g.drawString(font,UiTypography.bindingText(action),cell.x()+2,textY,0xFFADB7C8,false);
            g.drawString(font,UiTypography.bindingText(value),cell.x()+labelW+8,textY,0xFFE8EAF0,false);
            g.disableScissor();
            if(mx>=cell.x()&&mx<cell.x()+cell.w()&&my>=cell.y()&&my<cell.y()+cell.h()&&my>=42&&my<bottom()
                    &&(!action.equals(cell.action())||!value.equals(cell.value())))
                g.renderTooltip(font,UiTypography.text(cell.action()+" — "+cell.value()),mx,my);
        }
    }
    private void volume() {
        int y=cursor;cursor+=28;
        var slider=new UiSlider(x()+12,y,panelWidth()-24,24,SoundPrefs.volume(),v->ClientAudioHandler.setVolume((float)v),v->ModTexts.c("tv.volume",(int)Math.round(v*100))) {
            @Override public void onRelease(double mouseX, double mouseY) {super.onRelease(mouseX, mouseY);SoundPrefs.setVolume((float)value);}
            @Override protected void applyValue(){super.applyValue();SoundPrefs.setVolume((float)value);}
        };
        body.add(addRenderableWidget(slider));
    }
    private void button(Component label,Runnable action,boolean primary) {
        var b=new LibraryButton(x()+12,cursor,panelWidth()-24,buttonH(),label,ignored->action.run());
        if(primary)b.primary();body.add(addRenderableWidget(b));cursor+=buttonH()+3;
    }
    private void note(String text) {
        for(var line:font.split(UiTypography.text(text),panelWidth()-24)) {
            // Keep visual-order chars and styles when rendering; store plain text only for these UI prose labels.
            StringBuilder str=new StringBuilder();line.accept((i,s,cp)->{str.appendCodePoint(cp);return true;});
            lines.add(new Line(str.toString(),cursor,0xFFADB7C8));cursor+=12;
        }
        cursor+=10;
    }
    @Override public void render(GuiGraphics g,int mx,int my,float pt) {
        if(!open)return;
g.pose().pushPose(); g.pose().translate(0,0,400); try {
        if(page==Page.BEZEL){bezel.renderBackground(g,mx,my,pt);bezel.render(g,mx,my,pt);return;}
        g.fill(x(),0,width,height,0xFF181D25);g.fill(x(),0,x()+1,height,0xFF4D5769);
        int tx=headerTextX();
        String heading=page==Page.MAIN?owner.gameName():ModTexts.s(switch(page){case SAVES->"play.saves";case CONTROLS->"play.controls";default->"play.av";});
        g.drawString(font,UiTypography.text(UiTypography.trim(font,heading,width-40-tx)),tx,16,0xFFE8EAF0,false);
        g.drawString(font,UiTypography.text(page==Page.MAIN?ModTexts.s("play.menu"):UiTypography.trim(font,owner.gameName(),width-40-tx)),tx,29,0xFFADB7C8,false);
        g.fill(x()+1,40,width,41,0xFF353F50);
        g.enableScissor(x()+1,42,width,bottom());
        for(var line:lines)g.drawString(font,UiTypography.text(line.text()),x()+12,line.y(),line.color(),false);
        renderBindings(g,mx,my);
        for(var widget:body)widget.render(g,mx,my,pt);
        g.disableScissor();
        if(maxScroll>0) {
            int h=Math.max(12,(bottom()-44)*(bottom()-44)/(bottom()-44+maxScroll));
            int sy=44+(bottom()-44-h)*scroll/Math.max(1,maxScroll);
            g.fill(width-4,44,width-2,bottom(),0xFF353F50);g.fill(width-4,sy,width-2,sy+h,0xFF818FBF);
        }
        if(page==Page.MAIN)g.fill(x()+1,height-50,width,height-49,0xFF353F50);
        for(var widget:chrome)widget.render(g,mx,my,pt);
    } finally { g.pose().popPose(); }
}
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if(keyCode==293){closeMenu();return true;}
        if(page==Page.BEZEL)return bezel.keyPressed(keyCode, scanCode, modifiers);
        if(keyCode==256){if(page==Page.MAIN)closeMenu();else page(Page.MAIN);return true;}
        if(keyCode==294){owner.requestState(true);return true;}
        if(keyCode==295){owner.requestState(false);return true;}
        if(keyCode==266||keyCode==267){scrollBy(keyCode==266?-72:72);return true;}
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public boolean keyReleased(int keyCode, int scanCode, int modifiers){return page==Page.BEZEL?bezel.keyReleased(keyCode, scanCode, modifiers):super.keyReleased(keyCode, scanCode, modifiers);}
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if(page==Page.BEZEL)return bezel.mouseClicked(mouseX, mouseY, button);
        if(mouseX<x())return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }
    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy){return page==Page.BEZEL?bezel.mouseDragged(mouseX, mouseY, button, dx, dy):super.mouseDragged(mouseX, mouseY, button, dx, dy);}
    @Override public boolean mouseReleased(double mouseX, double mouseY, int button){return page==Page.BEZEL?bezel.mouseReleased(mouseX, mouseY, button):super.mouseReleased(mouseX, mouseY, button);}
    @Override public boolean mouseScrolled(double mx,double my,double hx,double hy){if(page==Page.BEZEL)return bezel.mouseScrolled(mx,my,hx,hy);scrollBy((int)(-hy*24));return true;}
    private void scrollBy(int amount){int next=Math.max(0,Math.min(maxScroll,scroll+amount));if(next!=scroll){scroll=next;rebuildWidgets();}}
    @Override public void onClose(){closeMenu();}
    @Override public boolean isPauseScreen(){return false;}
}
