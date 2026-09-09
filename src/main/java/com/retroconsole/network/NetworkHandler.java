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
import java.util.UUID;

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

        r.playToServer(RetroPointerPacket.TYPE, RetroPointerPacket.STREAM_CODEC,
                NetworkHandler::handlePointer);
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
        r.playToServer(RetroCoopPacket.TYPE, RetroCoopPacket.STREAM_CODEC,
                NetworkHandler::handleCoop);
    }

    private static void handlePointer(RetroPointerPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withConsole(ctx, pkt.pos(), console -> {
            UUID consoleId = console.getOrAssignConsoleId();
            ServerConsoles.handlePointer(consoleId, pkt.x(), pkt.y(), pkt.pressed());
        }));
    }

    private static void handleInput(RetroInputPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withConsole(ctx, pkt.pos(), console -> {
            UUID consoleId = console.getOrAssignConsoleId();
            int port = ServerConsoles.getPort(consoleId, ctx.player().getUUID());
            ServerConsoles.handleInput(consoleId, port, pkt.buttonId(), pkt.pressed());
        }));
    }

    private static void handleAnalog(RetroAnalogPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withConsole(ctx, pkt.pos(), console -> {
            int port = ServerConsoles.getPort(console.getOrAssignConsoleId(), ctx.player().getUUID());
            var core = console.getCore();
            if (core == null) return;
            core.setAnalog(port, 0, 0, pkt.lx());
            core.setAnalog(port, 0, 1, pkt.ly());
            core.setAnalog(port, 1, 0, pkt.rx());
            core.setAnalog(port, 1, 1, pkt.ry());
        }));
    }

    private static void handleView(RetroViewPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!(player.level().getBlockEntity(pkt.pos()) instanceof RetroConsoleBlockEntity console)) return;
            UUID consoleId = console.getOrAssignConsoleId();
            if (pkt.watching()) {
                if (!isNear(player, pkt.pos(), viewSubscribeDistanceSq())) return;
                ServerConsoles.addViewer(consoleId, player.getUUID());
            } else {
                ServerConsoles.removeViewer(consoleId, player.getUUID());
            }
        });
    }

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
        ctx.enqueueWork(() -> withConsole(ctx, pkt.pos(), console -> {
            ServerConsoles.handleSaveState(
                    console.getConsoleId(), pkt.slot(), pkt.save(), pkt.auto());
        }));
    }

    private static void handlePowerOff(RetroPowerOffPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> withConsole(ctx, pkt.pos(), console -> {
            if (!ServerConsoles.isOwner(console.getConsoleId(), ctx.player().getUUID())) return;
            console.powerOff();
        }));
    }

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

    private static void handleCoop(RetroCoopPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!isNear(player, pkt.pos(), controlDistanceSq())) return;
            if (!(player.level().getBlockEntity(pkt.pos()) instanceof RetroConsoleBlockEntity console)) return;
            UUID consoleId = console.getOrAssignConsoleId();
            if (!ServerConsoles.hasEmulator(consoleId)) return;
            UUID uuid = player.getUUID();
            if (pkt.join()) {
                boolean ok = ServerConsoles.joinCoop(consoleId, uuid);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        ok ? "[RetroConsole] Joined as Player 2" : "[RetroConsole] P2 slot is taken"));
            } else {
                ServerConsoles.leaveCoop(consoleId, uuid);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[RetroConsole] Left Player 2 — back to shared P1"));
            }
        });
    }

    private static void withConsole(IPayloadContext ctx, BlockPos pos,
                                     java.util.function.Consumer<RetroConsoleBlockEntity> action) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!isNear(player, pos, controlDistanceSq())) return;
        if (!(player.level().getBlockEntity(pos)
                instanceof RetroConsoleBlockEntity console)) return;
        UUID consoleId = console.getOrAssignConsoleId();
        if (!ServerConsoles.hasEmulator(consoleId)) return;
        action.accept(console);
    }
}
