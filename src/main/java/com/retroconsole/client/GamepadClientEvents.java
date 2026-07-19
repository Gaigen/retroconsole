package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public final class GamepadClientEvents {

    private GamepadClientEvents() {}

    @SubscribeEvent
    public static void hideHudWhilePlaying(RenderGuiLayerEvent.Pre event) {
        if (!(Minecraft.getInstance().screen instanceof GamepadScreen)) return;
        if (event.getName().equals(VanillaGuiLayers.CROSSHAIR)
                || event.getName().equals(VanillaGuiLayers.HOTBAR)) {
            event.setCanceled(true);
        }
    }
}
