package com.retroconsole.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-agnostic access to the running server and player-bound packet
 * delivery. Each loader provides the implementation at startup:
 * NeoForge uses ServerLifecycleHooks + PacketDistributor, Fabric uses
 * FabricLoader + ServerPlayNetworking.
 */
public final class ServerAccess {

    private static MinecraftServer currentServer;
    private static PlayerSender playerSender = (player, packet) -> { };

    private ServerAccess() {}

    public interface PlayerSender {
        void send(ServerPlayer player, Object packet);
    }

    /** Called by the loader entrypoint (both sides). */
    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }

    /** Called by the loader entrypoint to wire packet delivery. */
    public static void setPlayerSender(PlayerSender sender) {
        playerSender = sender != null ? sender : (player, packet) -> { };
    }

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        playerSender.send(player, packet);
    }
}
