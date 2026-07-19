package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import com.retroconsole.item.GamepadScreens;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public class ClientLifecycle {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        GamepadScreens.setOpener(pos -> Minecraft.getInstance().setScreen(new GamepadScreen(pos)));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAudioHandler.stopAll();
    }
}
