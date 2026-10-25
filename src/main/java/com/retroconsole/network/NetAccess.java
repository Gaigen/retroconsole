package com.retroconsole.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-agnostic network send access.
 * NeoForge: PacketDistributor. Fabric: ServerPlayNetworking / ClientPlayNetworking.
 * Payload-handler registration stays in each loader's module
 * (NeoForge: RegisterPayloadHandlersEvent, Fabric: PayloadTypeRegistry).
 */
public final class NetAccess {

    private NetAccess() {}

    private static Sender playerSender = (player, packet) -> { };
    private static ServerSender serverSender = packet -> { };

    /** Send a payload to the server from the client. */
    public static void sendToServer(Object packet) {
        serverSender.send(packet);
    }

    /** Send a payload to a specific player from the server. */
    public static void sendToPlayer(ServerPlayer player, Object packet) {
        playerSender.send(player, packet);
    }

    /** Called by the loader entrypoint (client side). */
    public static void setServerSender(ServerSender serverSender) {
        if (serverSender != null) NetAccess.serverSender = serverSender;
    }

    /** Called by the loader entrypoint (server side). */
    public static void setPlayerSender(Sender playerSender) {
        if (playerSender != null) NetAccess.playerSender = playerSender;
    }

    public interface Sender {
        void send(ServerPlayer player, Object packet);
    }

    public interface ServerSender {
        void send(Object packet);
    }
}
