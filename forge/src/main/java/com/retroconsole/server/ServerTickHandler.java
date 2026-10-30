package com.retroconsole.server;

import com.retroconsole.RetroConsole;
import com.retroconsole.config.ConfigAccess;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;

import java.util.UUID;

@EventBusSubscriber(modid = RetroConsole.MOD_ID)
public class ServerTickHandler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (var level : event.getServer().getAllLevels()) {
            ServerConsoles.tick(level);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Facade for shared code: the running server, loader-independent.
        ServerAccess.setCurrentServer(event.getServer());
        // Re-read config/retroconsole.json so edits made while the game was off apply.
        ConfigAccess.reload();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerAccess.setCurrentServer(null);
        ServerConsoles.stopAll();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        ServerConsoles.removeViewerEverywhere(uuid);
        ServerConsoles.releasePortEverywhere(uuid);
        ServerConsoles.forgetAudioMuted(uuid);
    }
}