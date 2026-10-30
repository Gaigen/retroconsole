package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;

@EventBusSubscriber(modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public final class GamepadClientEvents {

    private GamepadClientEvents() {}

    @SubscribeEvent
    public static void hideHudWhilePlaying(RenderGuiOverlayEvent.Pre event) {
        if (!(Minecraft.getInstance().screen instanceof GamepadScreen)) return;
        if (event.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id())
                || event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            event.setCanceled(true);
        }
    }
}
