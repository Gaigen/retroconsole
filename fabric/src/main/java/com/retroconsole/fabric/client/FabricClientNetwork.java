package com.retroconsole.fabric.client;

import com.retroconsole.client.ClientPacketHandlers;
import com.retroconsole.fabric.FabricPayload;
import com.retroconsole.network.payload.NetHandler;
import com.retroconsole.network.payload.NetPayload;
import com.retroconsole.network.payload.RetroPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client-bound receivers, registered in a client-only class so the dedicated
 * server never resolves {@link ClientPlayNetworking}.
 */
public final class FabricClientNetwork {

    private FabricClientNetwork() {}

    public static void register() {
        for (NetPayload<?> definition : RetroPayloads.CLIENT_BOUND) {
            registerReceiver(definition);
        }
    }

    private static <T> void registerReceiver(NetPayload<T> definition) {
        NetHandler<T> handler = ClientPacketHandlers.handler(definition);
        ClientPlayNetworking.registerGlobalReceiver(FabricPayload.type(definition),
                (payload, context) -> handler.handle(payload.value(), new FabricClientContext(context)));
    }
}
