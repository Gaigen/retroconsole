package com.retroconsole.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import java.util.function.BooleanSupplier;

/** Native focus, narration and activation, with a flat library-only presentation. */
public final class LibraryButton extends Button {
    private BooleanSupplier selected = () -> false;
    private boolean primary;
    private boolean settingsIcon;
    public LibraryButton settingsIcon() { settingsIcon=true; return this; }

    public LibraryButton(int x, int y, int w, int h, Component message, OnPress action) {
        super(x, y, w, h, message, action, DEFAULT_NARRATION);
    }
    public LibraryButton selected(BooleanSupplier value) { selected = value; return this; }
    public LibraryButton primary() { primary = true; return this; }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float pt) {
        boolean focus = isHoveredOrFocused();
        boolean chosen = selected.getAsBoolean();
        int edge = !active ? 0xFF343A46 : focus ? 0xFFB8C3FF : chosen ? 0xFF818FBF : 0xFF596273;
        int fill = primary && active ? 0xFFB8C3FF : chosen ? 0xFF272E40 : focus && active ? 0xFF252C38 : 0xFF181D25;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        g.fill(x, y, x + w, y + h, edge);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        if (isFocused()) g.fill(x + 3, y + h - 3, x + w - 3, y + h - 2, 0xFFDAE0FF);
        if(settingsIcon) {
            int cx=x+w/2, cy=y+h/2, c=active?0xFFE8EAF0:0xFF8991A0;
            for(int row=-1;row<=1;row++) {
                int yy=cy+row*4;g.fill(cx-5,yy,cx+6,yy+1,c);
                int knob=cx+(row==0?2:-2);g.fill(knob-1,yy-1,knob+2,yy+2,c);
            }
            return;
        }
        var font = Minecraft.getInstance().font;
        // The native collector preserves clipping and hover text for long translations.
        var label = UiTypography.text(getMessage()).copy().withStyle(s -> s.withColor(
                !active ? 0xFF8991A0 : primary ? 0xFF171C2B : 0xFFE8EAF0));
        extractScrollingStringOverContents(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE), label, 4);
    }

    static final class Builder extends Button.Builder {
        private final Component label;
        private final OnPress action;
        private int x, y, w = 150, h = 20;
        private Tooltip tooltip;
        Builder(Component label, OnPress action) { super(label, action); this.label = label; this.action = action; }
        @Override public Button.Builder pos(int x, int y) { this.x = x; this.y = y; return this; }
        @Override public Button.Builder size(int w, int h) { this.w = w; this.h = h; return this; }
        @Override public Button.Builder width(int w) { this.w = w; return this; }
        @Override public Button.Builder bounds(int x, int y, int w, int h) { pos(x, y); return size(w, h); }
        @Override public Button.Builder tooltip(Tooltip value) { tooltip = value; return this; }
        @Override public Button build() {
            LibraryButton button = new LibraryButton(x, y, w, h, label, action);
            button.setTooltip(tooltip);
            return button;
        }
    }
}
