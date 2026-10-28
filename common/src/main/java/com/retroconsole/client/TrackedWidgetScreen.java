package com.retroconsole.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen that remembers the widgets it added.
 *
 * <p>These screens draw their own layers in a fixed order instead of calling
 * {@code super.render}, so they have to render the buttons themselves. Vanilla
 * keeps {@code Screen.renderables} private and only NeoForge patches it open, so
 * this class keeps its own list — shared code then compiles against unpatched
 * Minecraft, which is what lets the Fabric module build it.
 */
public abstract class TrackedWidgetScreen extends Screen {

    private final List<Renderable> widgets = new ArrayList<>();

    protected TrackedWidgetScreen(Component title) {
        super(title);
    }

    @Override
    protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
        widgets.add(widget);
        return super.addRenderableWidget(widget);
    }

    @Override
    protected void clearWidgets() {
        widgets.clear();
        super.clearWidgets();
    }

    /** Draws the tracked widgets in the order they were added. */
    protected void renderTrackedWidgets(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        for (Renderable widget : widgets) {
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }
}
