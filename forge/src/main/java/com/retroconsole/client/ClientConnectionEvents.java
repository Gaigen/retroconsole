package com.retroconsole.client;
import com.retroconsole.RetroConsole;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
@EventBusSubscriber(modid=RetroConsole.MOD_ID,value=Dist.CLIENT)
public final class ClientConnectionEvents {
    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientAccess.firePlayerJoin();
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAccess.firePlayerLeave();
    }
}
