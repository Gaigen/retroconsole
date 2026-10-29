package com.retroconsole.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import java.util.function.Supplier;

/** Explicit opt-in, with no emulator/view session opened or closed by this screen. */
public final class UiAppearanceScreen extends Screen {
    private final Screen parent;
    private final Supplier<Screen> reopen;
    private boolean changed;
    private boolean modernChoice;

    public UiAppearanceScreen(Screen parent, Supplier<Screen> reopen) {
        super(ModTexts.c("appearance.title"));
        this.parent = parent;
        this.reopen = reopen;
    }

    @Override protected void init() {
        modernChoice = ClientUi.modern();
        int w = Math.min(280, width - 32), x = (width - w) / 2;
        int top = Math.max(38, height / 2 - 40);
        for (int i = 0; i < 2; i++) {
            final boolean modern = i == 1;
            LibraryButton button = new LibraryButton(x, top + i * 30, w, 24,
                    ModTexts.c(modern ? "appearance.modern" : "appearance.classic"), b -> {
                        ClientUi.setModern(modern);
                        modernChoice = ClientUi.modern();
                        changed = true;
                    }).selected(() -> modernChoice == modern);
            addRenderableWidget(button);
        }
        addRenderableWidget(new LibraryButton(x, Math.min(height - 28, top + 94), w, 24, CommonComponents.GUI_DONE, b -> onClose()));
    }

    @Override public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xFF101318);
    }
    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g,mx,my,pt);
        var heading=UiTypography.text(title); g.drawString(font,heading,(width-font.width(heading))/2,18,0xFFE8EAF0,false);
        int y = Math.max(38, height / 2 - 40) + 66;
        var lines = font.split(UiTypography.text(ModTexts.c("appearance.scope")), Math.min(280, width - 32));
        for (var line : lines) {
            g.drawString(font,line,(width-font.width(line))/2,y,0xFFABB4C5,false);
            y += 10;
        }
        super.render(g, mx, my, pt);
    }
    @Override public void onClose() { minecraft.setScreen(changed ? reopen.get() : parent); }
    @Override public boolean isPauseScreen() { return false; }
}
