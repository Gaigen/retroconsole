package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import com.retroconsole.client.ClientPacketHandlers;
import com.retroconsole.network.payload.NetHandler;
import com.retroconsole.network.payload.NetPayload;
import com.retroconsole.network.payload.RetroPayloads;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge payload registration: two loops over {@link RetroPayloads} replace the
 * old hand-written list, so a new packet only needs an entry in the table.
 *
 * <p>This is loader glue, so it is the only place allowed to name
 * {@link ClientPacketHandlers}: the client handler table is looked up inside the
 * handler lambda, which never runs on a dedicated server.
 */
@EventBusSubscriber(modid = RetroConsole.MOD_ID)
public final class NeoForgeNetwork {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Net");

    private NeoForgeNetwork() {}

    @SubscribeEvent
    public static void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        for (NetPayload<?> definition : RetroPayloads.CLIENT_BOUND) {
            registerToClient(registrar, definition);
        }
        for (NetPayload<?> definition : RetroPayloads.SERVER_BOUND) {
            registerToServer(registrar, definition);
        }

        LOGGER.info("RetroConsole payloads: {} registered (neoforge)", RetroPayloads.ALL.size());
    }

    private static <T> void registerToClient(PayloadRegistrar registrar, NetPayload<T> definition) {
        registrar.playToClient(NeoPayload.type(definition), NeoPayload.codec(definition),
                (payload, context) -> ClientPacketHandlers.handler(definition)
                        .handle(payload.value(), new NeoContext(context)));
    }

    private static <T> void registerToServer(PayloadRegistrar registrar, NetPayload<T> definition) {
        NetHandler<T> handler = RetroPayloads.serverHandler(definition);
        registrar.playToServer(NeoPayload.type(definition), NeoPayload.codec(definition),
                (payload, context) -> handler.handle(payload.value(), new NeoContext(context)));
    }
}
