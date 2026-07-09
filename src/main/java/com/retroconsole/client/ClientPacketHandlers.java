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
 * Клиентские хендлеры S2C-пакетов. Класс намеренно изолирован от
 * NetworkHandler: он импортирует client-only классы (Minecraft, экраны),
 * и его загрузка на dedicated server уронила бы сервер.
 * NetworkHandler ссылается сюда только через лямбды.
 */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    /**
     * Всё на сетевом потоке: распаковка СРАЗУ в ABGR (один попиксельный
     * проход) + submitFrame. enqueueWork для кадров НЕ используется —
     * очередь рендера не растёт.
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
     * Пара к серверному клику в RetroConsoleBlock.useItemOn: сервер решает,
     * что консоль готова (или перезапущена с автосейва), клиент только
     * открывает нужный экран. Пустой romId = меню выбора игры.
     */
    public static void handleOpenScreen(RetroOpenScreenPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (pkt.romId() == null || pkt.romId().isEmpty()) {
                Minecraft.getInstance().setScreen(new CoreSelectScreen(pkt.pos()));
            } else {
                Minecraft.getInstance().setScreen(new TvScreen(pkt.pos(), pkt.romId()));
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
