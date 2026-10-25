package com.retroconsole.network;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.config.RetroSettings;
import com.retroconsole.library.ArtFiles;
import com.retroconsole.library.GameSystem;
import com.retroconsole.library.RomLibrary;
import com.retroconsole.network.payload.NetContext;
import com.retroconsole.network.payload.RetroPayloads;
import com.retroconsole.platform.RetroConsolePaths;
import com.retroconsole.server.ServerConsoles;
import com.retroconsole.server.ServerPlayStats;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server-side C2S payload handlers. Loader-agnostic: everything that differs
 * between Forge and Fabric lives behind {@link NetContext} and {@link NetAccess}.
 */
public final class ServerPayloadHandlers {

    private ServerPayloadHandlers() {}

    public static void handlePointer(RetroPointerPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> withConsole(ctx, pkt.pos(), console -> {
            UUID consoleId = console.getOrAssignConsoleId();
            ServerConsoles.handlePointer(consoleId, pkt.x(), pkt.y(), pkt.pressed());
        }));
    }

    public static void handleKey(RetroKeyPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> withConsole(ctx, pkt.pos(), console ->
                ServerConsoles.handleKey(console.getOrAssignConsoleId(), pkt.key(), pkt.pressed())));
    }

    public static void handleMouse(RetroMousePacket pkt, NetContext ctx) {
        ctx.enqueue(() -> withConsole(ctx, pkt.pos(), console ->
                ServerConsoles.handleMouse(console.getOrAssignConsoleId(),
                        pkt.dx(), pkt.dy(), pkt.buttons(), pkt.wheel())));
    }

    public static void handleInput(RetroInputPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> withConsole(ctx, pkt.pos(), console -> {
            UUID consoleId = console.getOrAssignConsoleId();
            int port = ServerConsoles.getPort(consoleId, ctx.player().getUUID());
            ServerConsoles.handleInput(consoleId, port, pkt.buttonId(), pkt.pressed());
        }));
    }

    public static void handleAnalog(RetroAnalogPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> withConsole(ctx, pkt.pos(), console -> {
            int port = ServerConsoles.getPort(console.getOrAssignConsoleId(), ctx.player().getUUID());
            var core = console.getCore();
            if (core == null) return;
            core.setAnalog(port, 0, 0, pkt.lx());
            core.setAnalog(port, 0, 1, pkt.ly());
            core.setAnalog(port, 1, 0, pkt.rx());
            core.setAnalog(port, 1, 1, pkt.ry());
        }));
    }

    public static void handleView(RetroViewPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> {
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

    public static void handleCoreSelect(RetroCoreSelectPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!isNear(player, pkt.pos(), controlDistanceSq())) return;
            if (player.level().getBlockEntity(pkt.pos())
                    instanceof RetroConsoleBlockEntity console) {
                console.selectGame(pkt.coreName(), pkt.romId(), player.getUUID(), pkt.loadAuto());
            }
        });
    }

    public static void handleSaveState(RetroSaveStatePacket pkt, NetContext ctx) {
        ctx.enqueue(() -> withConsole(ctx, pkt.pos(), console -> {
            ServerConsoles.handleSaveState(
                    console.getConsoleId(), pkt.slot(), pkt.save(), pkt.auto());
        }));
    }

    public static void handlePowerOff(RetroPowerOffPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> withConsole(ctx, pkt.pos(), console -> {
            if (!ServerConsoles.isOwner(console.getConsoleId(), ctx.player().getUUID())) return;
            console.powerOff();
        }));
    }

    public static void handleLibraryRequest(RetroLibraryRequestPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!isNear(player, pkt.consolePos(), controlDistanceSq())) return;
            if (!(player.level().getBlockEntity(pkt.consolePos())
                    instanceof RetroConsoleBlockEntity)) return;
            RomLibrary lib = new RomLibrary();
            lib.scan();
            ArtFiles.ensureForSystems(RetroConsolePaths.artDir(), GameSystem.all());
            var stats = ServerPlayStats.exportFor(player.getUUID());
            RetroLibraryPacket library = RetroLibraryPacket.from(pkt.consolePos(), lib, stats);
            NetAccess.sendToPlayer(player, RetroPayloads.LIBRARY, library);

            Set<String> folders = new HashSet<>();
            for (RetroLibraryPacket.SystemEntry s : library.systems()) {
                folders.add(s.folder());
            }
            var images = ArtFiles.loadPacketEntries(folders);
            if (!images.isEmpty()) {
                NetAccess.sendToPlayer(player, RetroPayloads.ART,
                        new RetroArtPacket(pkt.consolePos(), images));
            }
        });
    }

    public static void handleCoop(RetroCoopPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> {
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

    /** Muted players stop receiving PCM entirely (saves ~176 KB/s per console). */
    public static void handleAudioSettings(RetroAudioSettingsPacket pkt, NetContext ctx) {
        ctx.enqueue(() -> ServerConsoles.setAudioMuted(ctx.player().getUUID(), pkt.muted()));
    }

    private static boolean isNear(ServerPlayer player, BlockPos pos, double distSq) {
        return player.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= distSq;
    }

    private static double controlDistanceSq() {
        int d = RetroSettings.controlDistance();
        return (double) d * d;
    }

    private static double viewSubscribeDistanceSq() {
        int d = RetroSettings.viewSubscribeDistance();
        return (double) d * d;
    }

    private static void withConsole(NetContext ctx, BlockPos pos, Consumer<RetroConsoleBlockEntity> action) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!isNear(player, pos, controlDistanceSq())) return;
        if (!(player.level().getBlockEntity(pos)
                instanceof RetroConsoleBlockEntity console)) return;
        UUID consoleId = console.getOrAssignConsoleId();
        if (!ServerConsoles.hasEmulator(consoleId)) return;
        action.accept(console);
    }
}
