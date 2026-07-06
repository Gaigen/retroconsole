package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public class ClientLifecycle {

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAudioHandler.stopAll();
    }
}
