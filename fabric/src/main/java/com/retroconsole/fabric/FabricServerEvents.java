package com.retroconsole.fabric;

import com.retroconsole.config.ConfigAccess;
import com.retroconsole.server.ServerAccess;
import com.retroconsole.server.ServerConsoles;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Server lifecycle glue, the Fabric counterpart of {@code ServerTickHandler}:
 * console ticking, the current-server facade, config reload and per-player
 * cleanup on disconnect.
 */
public final class FabricServerEvents {

    private FabricServerEvents() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerLevel level : server.getAllLevels()) {
                ServerConsoles.tick(level);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Facade for shared code: the running server, loader-independent.
            ServerAccess.setCurrentServer(server);
            // Re-read config/retroconsole.json so edits made while the game was off apply.
            ConfigAccess.reload();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ServerAccess.setCurrentServer(null);
            ServerConsoles.stopAll();
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUUID();
            ServerConsoles.removeViewerEverywhere(uuid);
            ServerConsoles.releasePortEverywhere(uuid);
            ServerConsoles.forgetAudioMuted(uuid);
        });
    }
}
