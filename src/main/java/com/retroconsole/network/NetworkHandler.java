package com.retroconsole.network;

import com.retroconsole.RetroConsole;
import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.client.ClientPacketHandlers;
import com.retroconsole.library.RomLibrary;
import com.retroconsole.server.ServerConsoles;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.Consumer;

@EventBusSubscriber(modid = RetroConsole.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class NetworkHandler {

    /** Дистанция управления консолью (ввод, стики, сейвы, выкл, выбор игры) — как у контейнеров. */
    private static final double CONTROL_DISTANCE_SQ = 8.0 * 8.0;

    /** Дистанция подписки на видеопоток — экран телевизора видно издалека. */
    private static final double VIEW_DISTANCE_SQ = 64.0 * 64.0;

    private NetworkHandler() {}

    @SubscribeEvent
    public static void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        // Client-bound.
        // ВАЖНО: только лямбды, не method references! Ссылка вида
        // ClientPacketHandlers::handleFrame заставила бы JVM загрузить
        // клиентский класс уже при регистрации — краш dedicated server.
        r.playToClient(RetroFramePacket.TYPE, RetroFramePacket.STREAM_CODEC,
                (pkt, ctx) -> ClientPacketHandlers.handleFrame(pkt, ctx));
        r.playToClient(RetroAudioPayload.TYPE, RetroAudioPayload.STREAM_CODEC,
                (pkt, ctx) -> ClientPacketHandlers.handleAudio(pkt, ctx));
        r.playToClient(RetroStopConsolePacket.TYPE, RetroStopConsolePacket.STREAM_CODEC,
                (pkt, ctx) -> ClientPacketHandlers.handleStopConsole(pkt, ctx));
        r.playToClient(RetroOpenScreenPacket.TYPE, RetroOpenScreenPacket.STREAM_CODEC,
                (pkt, ctx) -> ClientPacketHandlers.handleOpenScreen(pkt, ctx));
        r.playToClient(RetroLibraryPacket.TYPE, RetroLibraryPacket.STREAM_CODEC,
                (pkt, ctx) -> ClientPacketHandlers.handleLibrary(pkt, ctx));

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
        r.playToServer(RetroLibraryRequestPacket.TYPE, RetroLibraryRequestPacket.STREAM_CODEC,
                NetworkHandler::handleLibraryRequest);
    }

    // --- Server-bound handlers ---

    private static void handleInput(RetroInputPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withControlledConsole(ctx, pkt.pos(),
                console -> ServerConsoles.handleInput(pkt.pos(), pkt.buttonId(), pkt.pressed())));
    }

    /**
     * Полное аналоговое состояние геймпада. enqueueWork обязателен:
     * доступ к level.getBlockEntity() с сетевого потока небезопасен.
     * Дальше ввод пишется в lock-free AtomicIntegerArray моста —
     * задержка максимум один тик.
     */
    private static void handleAnalog(RetroAnalogPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withControlledConsole(ctx, pkt.pos(), console -> {
            var core = console.getCore();
            if (core == null) return;
            core.setAnalog(0, 0, pkt.lx());
            core.setAnalog(0, 1, pkt.ly());
            core.setAnalog(1, 0, pkt.rx());
            core.setAnalog(1, 1, pkt.ry());
        }));
    }

    private static void handleView(RetroViewPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (pkt.watching()) {
                // Подписка — только вблизи консоли.
                if (!isNear(player, pkt.pos(), VIEW_DISTANCE_SQ)) return;
                ServerConsoles.addViewer(pkt.pos(), player.getUUID());
            } else {
                // Отписку разрешаем с любой дистанции: игрок мог уже отойти.
                ServerConsoles.removeViewer(pkt.pos(), player.getUUID());
            }
        });
    }

    /**
     * Клиент выбрал core + ROM → передаём в block entity (запуск эмулятора).
     * Проверки «управляет ли игрок» здесь нет — водителя ещё не существует,
     * его назначает selectGame.
     */
    private static void handleCoreSelect(RetroCoreSelectPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!isNear(player, pkt.pos(), CONTROL_DISTANCE_SQ)) return;
            if (player.level().getBlockEntity(pkt.pos())
                    instanceof RetroConsoleBlockEntity console) {
                console.selectGame(pkt.coreName(), pkt.romId(), player.getUUID(), pkt.loadAuto());
            }
        });
    }

    private static void handleSaveState(RetroSaveStatePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withControlledConsole(ctx, pkt.pos(),
                console -> ServerConsoles.handleSaveState(pkt.pos(), pkt.slot(), pkt.save(), pkt.auto())));
    }

    /**
     * «Выкл» из TvScreen: гасим консоль ЧЕРЕЗ block entity, чтобы очистился
     * romId (иначе следующий ПКМ по блоку откроет TvScreen мёртвой консоли).
     * Автосейв произойдёт внутри ServerConsoles.stopEmulator().
     */
    private static void handlePowerOff(RetroPowerOffPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withControlledConsole(ctx, pkt.pos(),
                RetroConsoleBlockEntity::powerOff));
    }

    /** Клиент открыл меню выбора игры — отдаём каталог с диска сервера. */
    private static void handleLibraryRequest(RetroLibraryRequestPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!isNear(player, pkt.consolePos(), CONTROL_DISTANCE_SQ)) return;
            if (!(player.level().getBlockEntity(pkt.consolePos())
                    instanceof RetroConsoleBlockEntity)) return;
            RomLibrary lib = new RomLibrary();
            lib.scan();
            PacketDistributor.sendToPlayer(player,
                    RetroLibraryPacket.from(pkt.consolePos(), lib));
        });
    }

    // --- Общий анти-чит для управляющих пакетов ---

    private static boolean isNear(ServerPlayer player, BlockPos pos, double distSq) {
        return player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= distSq;
    }

    /**
     * Выполнить action, если: отправитель — игрок рядом с консолью (#1),
     * по позиции реально стоит консоль (#2) и именно этот игрок ей
     * управляет (#3). Иначе пакет молча игнорируется.
     */
    private static void withControlledConsole(IPayloadContext ctx, BlockPos pos,
                                              Consumer<RetroConsoleBlockEntity> action) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!isNear(player, pos, CONTROL_DISTANCE_SQ)) return;
        if (!(player.level().getBlockEntity(pos)
                instanceof RetroConsoleBlockEntity console)) return;
        if (!console.isControlledBy(player)) return;
        action.accept(console);
    }
}
