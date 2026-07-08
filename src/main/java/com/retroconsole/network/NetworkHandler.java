package com.retroconsole.network;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.client.ClientAudioHandler;
import com.retroconsole.client.ClientConsoles;
import com.retroconsole.client.CoreSelectScreen;
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

    /** Максимальная дистанция, с которой игрок может управлять консолью (выкл и т.п.). */
    private static final double CONTROL_DISTANCE_SQ = 64.0 * 64.0;

    @SubscribeEvent
    public static void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        // Client-bound
        r.playToClient(RetroFramePacket.TYPE, RetroFramePacket.STREAM_CODEC,
                NetworkHandler::handleFrame);
        r.playToClient(RetroAudioPayload.TYPE, RetroAudioPayload.STREAM_CODEC,
                ClientAudioHandler::handle);
        r.playToClient(RetroStopConsolePacket.TYPE, RetroStopConsolePacket.STREAM_CODEC,
                NetworkHandler::handleStopConsole);
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
        r.playToServer(RetroSaveStatePacket.TYPE, RetroSaveStatePacket.STREAM_CODEC,
                NetworkHandler::handleSaveState);
        r.playToServer(RetroPowerOffPacket.TYPE, RetroPowerOffPacket.STREAM_CODEC,
                NetworkHandler::handlePowerOff);
    }

    // --- Client-bound ---

    private static void handleFrame(RetroFramePacket pkt, IPayloadContext ctx) {
        // Всё на сетевом потоке: распаковка СРАЗУ в ABGR (один попиксельный
        // проход вместо двух) + submitFrame. enqueueWork для кадров
        // НЕ используется — очередь рендера не растёт.
        int[] frame = pkt.decompressFrameAbgr();
        if (frame != null) {
            ClientConsoles.submitFrame(pkt.pos(), frame, pkt.width(), pkt.height());
        }
    }

    private static void handleStopConsole(RetroStopConsolePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientConsoles.dispose(pkt.pos());
            ClientAudioHandler.stop(pkt.pos());
        });
    }

    /**
     * Пара к серверному клику в RetroConsoleBlock.useItemOn: сервер решает,
     * что консоль готова (или перезапущена с автосейва), а клиент
     * только открывает нужный экран. Пустой romId = меню выбора игры.
     */
    private static void handleOpenScreen(RetroOpenScreenPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (pkt.romId() == null || pkt.romId().isEmpty()) {
                Minecraft.getInstance().setScreen(new CoreSelectScreen(pkt.pos()));
            } else {
                Minecraft.getInstance().setScreen(new TvScreen(pkt.pos(), pkt.romId()));
            }
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

    /*
     * Client selects core + ROM → set on block entity (triggers emulator start).
     */
    private static void handleCoreSelect(RetroCoreSelectPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            BlockEntity be = ctx.player().level().getBlockEntity(pkt.pos());
            if (be instanceof RetroConsoleBlockEntity console) {
                console.selectGame(pkt.coreName(), pkt.romId(), ctx.player().getUUID(), pkt.loadAuto());
            }
        });
    }

    private static void handleSaveState(RetroSaveStatePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ServerConsoles.handleSaveState(
                pkt.pos(), pkt.slot(), pkt.save(), pkt.auto()));
    }

    /*
     * «Выкл» из TvScreen: гасим консоль ЧЕРЕЗ block entity, чтобы очистился
     * romId (иначе следующий ПКМ по блоку откроет TvScreen мёртвой консоли).
     * Автосейв произойдёт внутри ServerConsoles.stopEmulator().
     */
    private static void handlePowerOff(RetroPowerOffPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player().blockPosition().distSqr(pkt.pos()) > CONTROL_DISTANCE_SQ) return;
            BlockEntity be = ctx.player().level().getBlockEntity(pkt.pos());
            if (be instanceof RetroConsoleBlockEntity console) {
                console.powerOff();
            }
        });
    }
}