package com.retroconsole.client;

import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.network.RetroLibraryRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Server ROM/core catalog and per-player play stats (multiplayer). */
public final class ClientLibraryCache {

    private static RetroLibraryPacket pending;
    private static final Map<String, RetroLibraryPacket.StatEntry> STATS = new HashMap<>();
    private static String lastPlayedRomId;

    private ClientLibraryCache() {}

    public static boolean useServerLibrary() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && !mc.isLocalServer();
    }

    public static void request(BlockPos consolePos) {
        pending = null;
        clearStats();
        ClientArtCache.clear();
        PacketDistributor.sendToServer(new RetroLibraryRequestPacket(consolePos.immutable()));
    }

    public static void onReceived(RetroLibraryPacket pkt) {
        pending = pkt;
        applyStats(pkt.playerStats());
    }

    public static Optional<RetroLibraryPacket> poll(BlockPos consolePos) {
        if (pending != null && pending.consolePos().equals(consolePos)) {
            RetroLibraryPacket pkt = pending;
            pending = null;
            return Optional.of(pkt);
        }
        return Optional.empty();
    }

    public static long playtimeSec(String romId) {
        RetroLibraryPacket.StatEntry e = STATS.get(romId);
        return e == null ? 0 : e.playtimeSec();
    }

    public static int launches(String romId) {
        RetroLibraryPacket.StatEntry e = STATS.get(romId);
        return e == null ? 0 : e.launches();
    }

    public static long lastPlayed(String romId) {
        RetroLibraryPacket.StatEntry e = STATS.get(romId);
        return e == null ? 0 : e.lastPlayed();
    }

    public static String lastPlayedRomId() {
        return lastPlayedRomId;
    }

    private static void applyStats(List<RetroLibraryPacket.StatEntry> stats) {
        STATS.clear();
        lastPlayedRomId = null;
        long bestLast = 0;
        for (RetroLibraryPacket.StatEntry e : stats) {
            STATS.put(e.romId(), e);
            if (e.lastPlayed() > bestLast) {
                bestLast = e.lastPlayed();
                lastPlayedRomId = e.romId();
            }
        }
    }

    private static void clearStats() {
        STATS.clear();
        lastPlayedRomId = null;
    }
}
