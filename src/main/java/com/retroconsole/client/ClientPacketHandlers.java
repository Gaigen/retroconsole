package com.retroconsole.client;

import com.retroconsole.network.RetroArtPacket;
import com.retroconsole.network.RetroAudioPayload;
import com.retroconsole.network.RetroFramePacket;
import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.network.RetroOpenScreenPacket;
import com.retroconsole.network.RetroStopConsolePacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-side S2C packet handlers. Isolated from NetworkHandler because it imports
 * client-only classes (Minecraft, screens); loading it on a dedicated server would crash.
 * NetworkHandler references this class only via lambdas.
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    /**
     * All on the network thread: decompress straight to ABGR (single pixel pass) + submitFrame.
     * enqueueWork is NOT used for frames — avoids growing the render queue.
     */
    public static void handleFrame(RetroFramePacket pkt, IPayloadContext ctx) {
        int[] frame = pkt.decompressFrameAbgr();
        if (frame != null) {
            ClientConsoles.submitFrame(pkt.pos(), frame, pkt.width(), pkt.height());
        }
    }

    public static void handleAudio(RetroAudioPayload pkt, IPayloadContext ctx) {
        ClientAudioHandler.handle(pkt, ctx);
    }

    public static void handleStopConsole(RetroStopConsolePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientConsoles.dispose(pkt.pos());
            ClientAudioHandler.stop(pkt.pos());
        });
    }

    /**
     * Pairs with the server click in RetroConsoleBlock.useItemOn: the server decides
     * the console is ready (or restarted from autosave); the client only opens the screen.
     * Empty romId = game picker menu.
     */
    public static void handleOpenScreen(RetroOpenScreenPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (pkt.romId() == null || pkt.romId().isEmpty()) {
                Minecraft.getInstance().setScreen(new CoreSelectScreen(pkt.pos()));
            } else {
                Minecraft.getInstance().setScreen(new TvScreen(pkt.pos(), pkt.romId(), pkt.systemId()));
            }
        });
    }

    public static void handleArt(RetroArtPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientArtCache.apply(pkt));
    }

    public static void handleLibrary(RetroLibraryPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientLibraryCache.onReceived(pkt);
            if (Minecraft.getInstance().screen instanceof CoreSelectScreen screen
                    && screen.consolePos().equals(pkt.consolePos())) {
                screen.onServerLibraryReceived(pkt);
            }
        });
    }
}
