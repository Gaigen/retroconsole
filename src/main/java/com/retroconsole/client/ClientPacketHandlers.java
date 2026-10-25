package com.retroconsole.client;

import com.retroconsole.network.RetroArtPacket;
import com.retroconsole.network.RetroAudioPayload;
import com.retroconsole.network.RetroFramePacket;
import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.network.RetroOpenScreenPacket;
import com.retroconsole.network.RetroStopConsolePacket;
import com.retroconsole.network.payload.NetContext;
import com.retroconsole.network.payload.NetHandler;
import com.retroconsole.network.payload.NetPayload;
import com.retroconsole.network.payload.RetroPayloads;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side S2C payload handlers, plus the id-to-handler table the loader
 * registration glue looks up.
 *
 * <p>This class imports client-only types (Minecraft, screens), so it must stay
 * out of every shared module: {@code RetroPayloads} holds the payload data and
 * the server-side table only, and only loader glue touches this one — inside a
 * handler that can never run on a dedicated server.
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    private static final Map<String, NetHandler<?>> HANDLERS = new HashMap<>();

    static {
        HANDLERS.put(RetroPayloads.FRAME.id(), (NetHandler<RetroFramePacket>) ClientPacketHandlers::handleFrame);
        HANDLERS.put(RetroPayloads.AUDIO.id(), (NetHandler<RetroAudioPayload>) ClientPacketHandlers::handleAudio);
        HANDLERS.put(RetroPayloads.STOP_CONSOLE.id(), (NetHandler<RetroStopConsolePacket>) ClientPacketHandlers::handleStopConsole);
        HANDLERS.put(RetroPayloads.OPEN_SCREEN.id(), (NetHandler<RetroOpenScreenPacket>) ClientPacketHandlers::handleOpenScreen);
        HANDLERS.put(RetroPayloads.LIBRARY.id(), (NetHandler<RetroLibraryPacket>) ClientPacketHandlers::handleLibrary);
        HANDLERS.put(RetroPayloads.ART.id(), (NetHandler<RetroArtPacket>) ClientPacketHandlers::handleArt);
    }

    @SuppressWarnings("unchecked")
    public static <T> NetHandler<T> handler(NetPayload<T> definition) {
        NetHandler<?> handler = HANDLERS.get(definition.id());
        if (handler == null) {
            throw new IllegalStateException("No client handler for payload '" + definition.id() + "'");
        }
        return (NetHandler<T>) handler;
    }

    /**
     * All on the network thread: decompress straight to ABGR (single pixel pass) + submitFrame.
     * enqueueWork is NOT used for frames — avoids growing the render queue.
     */
    public static void handleFrame(RetroFramePacket pkt, NetContext ctx) {
        int[] frame = pkt.decompressFrameAbgr();
        if (frame != null) {
            ClientConsoles.submitFrame(pkt.consoleId(), frame, pkt.width(), pkt.height());
        }
    }

    public static void handleAudio(RetroAudioPayload pkt, NetContext ctx) {
        ClientAudioHandler.handle(pkt);
    }

    public static void handleStopConsole(RetroStopConsolePacket pkt, NetContext ctx) {
        ctx.enqueue(() -> {
            ClientConsoles.dispose(pkt.consoleId());
            ClientAudioHandler.stop(pkt.consoleId());
        });
    }

    /**
     * Pairs with the server click in RetroConsoleBlock.useItemOn: the server decides
     * the console is ready (or restarted from autosave); the client only opens the screen.
     * Empty romId = game picker menu.
     */
    public static void handleOpenScreen(RetroOpenScreenPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> {
            if (pkt.romId() == null || pkt.romId().isEmpty()) {
                Minecraft.getInstance().setScreen(new CoreSelectScreen(pkt.pos()));
            } else {
                Minecraft.getInstance().setScreen(
                        new TvScreen(pkt.pos(), pkt.consoleId(), pkt.romId(), pkt.systemId()));
            }
        });
    }

    public static void handleArt(RetroArtPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> ClientArtCache.apply(pkt));
    }

    public static void handleLibrary(RetroLibraryPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> {
            ClientLibraryCache.onReceived(pkt);
            if (Minecraft.getInstance().screen instanceof CoreSelectScreen screen
                    && screen.consolePos().equals(pkt.consolePos())) {
                screen.onServerLibraryReceived(pkt);
            }
        });
    }
}
