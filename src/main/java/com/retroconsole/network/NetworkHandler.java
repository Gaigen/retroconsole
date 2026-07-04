package com.retroconsole.network;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.client.ClientConsoles;
import com.retroconsole.client.TvScreen;
import com.retroconsole.server.ServerConsoles;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = "retroconsole", bus = EventBusSubscriber.Bus.MOD)
public class NetworkHandler {

    @SubscribeEvent
    public static void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        // Client-bound
        r.playToClient(RetroFramePacket.TYPE, RetroFramePacket.STREAM_CODEC,
                NetworkHandler::handleFrame);
        r.playToClient(RetroOpenScreenPacket.TYPE, RetroOpenScreenPacket.STREAM_CODEC,
                NetworkHandler::handleOpenScreen);

        // Server-bound
        r.playToServer(RetroInputPacket.TYPE, RetroInputPacket.STREAM_CODEC,
                NetworkHandler::handleInput);
        r.playToServer(RetroAnalogPacket.TYPE, RetroAnalogPacket.STREAM_CODEC,
                NetworkHandler::handleAnalog);
        r.playToServer(RetroViewPacket.TYPE, RetroViewPacket.STREAM_CODEC,
                NetworkHandler::handleView);
        r.playToServer(RetroCoreSelectPacket.TYPE, RetroCoreSelectPacket.STREAM_CODEC,
                NetworkHandler::handleCoreSelect);
    }

    // --- Client-bound ---

    private static void handleFrame(RetroFramePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            int[] frame = pkt.decompressFrame();
            if (frame != null) {
                // DEBUG: log black/non-black pixel count on the client.
                int black = 0, nonBlack = 0;
                for (int p : frame) {
                    if ((p & 0x00FFFFFF) == 0) black++; else nonBlack++;
                }
                int total = frame.length;
                System.out.println("DEBUG-FRAME-CLIENT size=" + pkt.width() + "x" + pkt.height()
                        + " total=" + total + " black=" + black + " nonBlack=" + nonBlack
                        + " blackPct=" + (total == 0 ? 0 : (100 * black) / total) + "%");
                ClientConsoles.updateFrame(pkt.pos(), frame, pkt.width(), pkt.height());
            }
        });
    }

    private static void handleOpenScreen(RetroOpenScreenPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new TvScreen(pkt.pos()));
        });
    }

    // --- Server-bound ---

    private static void handleInput(RetroInputPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerConsoles.handleInput(pkt.pos(), pkt.buttonId(), pkt.pressed());
        });
    }

    private static void handleAnalog(RetroAnalogPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerConsoles.handleAnalog(pkt.pos(), pkt.stick(), pkt.axis(), pkt.value());
        });
    }

    private static void handleView(RetroViewPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (pkt.watching()) {
                ServerConsoles.addViewer(pkt.pos(), ctx.player().getUUID());
            } else {
                ServerConsoles.removeViewer(pkt.pos(), ctx.player().getUUID());
            }
        });
    }

    /**
     * Client selects core + ROM → set on block entity (triggers emulator start).
     */
    private static void handleCoreSelect(RetroCoreSelectPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            BlockEntity be = ctx.player().level().getBlockEntity(pkt.pos());
            if (be instanceof RetroConsoleBlockEntity console) {
                console.setCoreName(pkt.coreName());
                console.setRomId(pkt.romId());
                // setRomId() triggers startEmulator() internally
            }
        });
    }
}
