package com.retroconsole.fabric;

import com.retroconsole.network.payload.NetPayload;
import com.retroconsole.network.payload.RetroPayloads;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric networking glue (common side): two loops over {@link RetroPayloads}
 * replace the hand-written per-packet registration, so a new packet only needs
 * an entry in the table.
 *
 * <p>Client-bound receivers are registered in {@code client/FabricClientNetwork}
 * — {@code ClientPlayNetworking} is a client-only class.
 */
public final class FabricNetwork {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Net");

    private FabricNetwork() {}

    public static void registerCommon() {
        int registered = 0;
        for (NetPayload<?> definition : RetroPayloads.CLIENT_BOUND) {
            registerToClient(definition);
            registered++;
        }
        for (NetPayload<?> definition : RetroPayloads.SERVER_BOUND) {
            registerToServer(definition);
            registered++;
        }

        // Counted rather than read off ALL.size(): a payload that fell out of both
        // direction lists would otherwise look registered until the first send.
        if (registered != RetroPayloads.ALL.size()) {
            throw new IllegalStateException("Registered " + registered + " of "
                    + RetroPayloads.ALL.size() + " RetroConsole payloads");
        }
        LOGGER.info("RetroConsole payloads: {} registered (fabric)", registered);
    }

    private static <T> void registerToClient(NetPayload<T> definition) {
        PayloadTypeRegistry.playS2C().register(FabricPayload.type(definition), FabricPayload.codec(definition));
    }

    private static <T> void registerToServer(NetPayload<T> definition) {
        PayloadTypeRegistry.playC2S().register(FabricPayload.type(definition), FabricPayload.codec(definition));
        ServerPlayNetworking.registerGlobalReceiver(FabricPayload.type(definition),
                (payload, context) -> RetroPayloads.serverHandler(definition)
                        .handle(payload.value(), new FabricContext(context)));
    }
}
