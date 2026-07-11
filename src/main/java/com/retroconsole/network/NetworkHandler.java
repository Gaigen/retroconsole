package com.retroconsole.network;

import com.retroconsole.RetroConsole;
import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.client.ClientPacketHandlers;
import com.retroconsole.config.ModConfig;
import com.retroconsole.library.ArtFiles;
import com.retroconsole.library.GameSystem;
import com.retroconsole.library.RomLibrary;
import com.retroconsole.platform.RetroConsolePaths;
import com.retroconsole.server.ServerConsoles;
import com.retroconsole.server.ServerPlayStats;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

@EventBusSubscriber(modid = RetroConsole.MOD_ID)
public final class NetworkHandler {

    private NetworkHandler() {}

    private static double controlDistanceSq() {
        int d = ModConfig.controlDistance();
        return (double) d * d;
    }

    private static double viewSubscribeDistanceSq() {
        int d = ModConfig.viewSubscribeDistance();
        return (double) d * d;
    }

    @SubscribeEvent
    public static void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar("1");

        // Client-bound. Lambdas only — method refs to ClientPacketHandlers would
        // load client classes during registration and crash the dedicated server.
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
        r.playToClient(RetroArtPacket.TYPE, RetroArtPacket.STREAM_CODEC,
                (pkt, ctx) -> ClientPacketHandlers.handleArt(pkt, ctx));

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

    private static void handleInput(RetroInputPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withControlledConsole(ctx, pkt.pos(),
                console -> ServerConsoles.handleInput(pkt.pos(), pkt.buttonId(), pkt.pressed())));
    }

    /** Full analog pad state; enqueueWork required for block entity access. */
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
                if (!isNear(player, pkt.pos(), viewSubscribeDistanceSq())) return;
                ServerConsoles.addViewer(pkt.pos(), player.getUUID());
            } else {
                ServerConsoles.removeViewer(pkt.pos(), player.getUUID());
            }
        });
    }

    /** Core + ROM pick — no controller check yet; selectGame assigns the driver. */
    private static void handleCoreSelect(RetroCoreSelectPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!isNear(player, pkt.pos(), controlDistanceSq())) return;
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

    /** Power off via block entity so romId clears; auto-save runs in stopEmulator(). */
    private static void handlePowerOff(RetroPowerOffPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withControlledConsole(ctx, pkt.pos(),
                RetroConsoleBlockEntity::powerOff));
    }

    /** Client opened game picker — send server disk catalog + art. */
    private static void handleLibraryRequest(RetroLibraryRequestPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!isNear(player, pkt.consolePos(), controlDistanceSq())) return;
            if (!(player.level().getBlockEntity(pkt.consolePos())
                    instanceof RetroConsoleBlockEntity)) return;
            RomLibrary lib = new RomLibrary();
            lib.scan();
            ArtFiles.ensureForSystems(RetroConsolePaths.artDir(), GameSystem.all());
            var stats = ServerPlayStats.exportFor(player.getUUID());
            RetroLibraryPacket library = RetroLibraryPacket.from(pkt.consolePos(), lib, stats);
            PacketDistributor.sendToPlayer(player, library);

            Set<String> folders = new HashSet<>();
            for (RetroLibraryPacket.SystemEntry s : library.systems()) {
                folders.add(s.folder());
            }
            var images = ArtFiles.loadPacketEntries(folders);
            if (!images.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new RetroArtPacket(pkt.consolePos(), images));
            }
        });
    }

    private static boolean isNear(ServerPlayer player, BlockPos pos, double distSq) {
        return player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= distSq;
    }

    /** Run action only if player is near, block is a console, and player controls it. */
    private static void withControlledConsole(IPayloadContext ctx, BlockPos pos,
                                              Consumer<RetroConsoleBlockEntity> action) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!isNear(player, pos, controlDistanceSq())) return;
        if (!(player.level().getBlockEntity(pos)
                instanceof RetroConsoleBlockEntity console)) return;
        if (!console.isControlledBy(player)) return;
        action.accept(console);
    }
}
