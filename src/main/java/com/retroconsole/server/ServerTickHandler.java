package com.retroconsole.server;

import com.retroconsole.RetroConsole;
import com.retroconsole.config.ConfigAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

@EventBusSubscriber(modid = RetroConsole.MOD_ID)
public class ServerTickHandler {

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
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