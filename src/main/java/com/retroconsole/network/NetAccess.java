package com.retroconsole.network;

import com.retroconsole.network.payload.NetPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader-agnostic network send access.
 * NeoForge: PacketDistributor. Fabric: ServerPlayNetworking / ClientPlayNetworking.
 *
 * <p>Payload-handler registration and the loader payload wrapper stay in each
 * loader's module; shared code only ever names a {@link NetPayload} definition.
 */
public final class NetAccess {

    private NetAccess() {}

    private static Sender playerSender = (player, definition, value) -> { };
    private static ServerSender serverSender = (definition, value) -> { };

    /** Send a payload to the server from the client. */
    public static <T> void sendToServer(NetPayload<T> definition, T value) {
        serverSender.send(definition, value);
    }

    /** Send a payload to a specific player from the server. */
    public static <T> void sendToPlayer(ServerPlayer player, NetPayload<T> definition, T value) {
        playerSender.send(player, definition, value);
    }

    /** Called by the loader entrypoint (client side). */
    public static void setServerSender(ServerSender sender) {
        if (sender != null) serverSender = sender;
    }

    /** Called by the loader entrypoint (both sides). */
    public static void setPlayerSender(Sender sender) {
        if (sender != null) playerSender = sender;
    }

    public interface Sender {
        void send(ServerPlayer player, NetPayload<?> definition, Object value);
    }

    public interface ServerSender {
        void send(NetPayload<?> definition, Object value);
    }
}
