package com.retroconsole.client;

import com.retroconsole.network.RetroLibraryPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Кэш серверной статистики из RetroLibraryPacket (только мультиплеер). */
public final class ClientPlayStatsCache {

    private static final Map<String, RetroLibraryPacket.StatEntry> BY_ROM = new HashMap<>();
    private static String lastPlayedRomId;

    private ClientPlayStatsCache() {}

    public static void apply(List<RetroLibraryPacket.StatEntry> stats) {
        BY_ROM.clear();
        lastPlayedRomId = null;
        long bestLast = 0;
        for (RetroLibraryPacket.StatEntry e : stats) {
            BY_ROM.put(e.romId(), e);
            if (e.lastPlayed() > bestLast) {
                bestLast = e.lastPlayed();
                lastPlayedRomId = e.romId();
            }
        }
    }

    public static void clear() {
        BY_ROM.clear();
        lastPlayedRomId = null;
    }

    public static long playtimeSec(String romId) {
        RetroLibraryPacket.StatEntry e = BY_ROM.get(romId);
        return e == null ? 0 : e.playtimeSec();
    }

    public static int launches(String romId) {
        RetroLibraryPacket.StatEntry e = BY_ROM.get(romId);
        return e == null ? 0 : e.launches();
    }

    public static long lastPlayed(String romId) {
        RetroLibraryPacket.StatEntry e = BY_ROM.get(romId);
        return e == null ? 0 : e.lastPlayed();
    }

    public static String lastPlayedRomId() {
        return lastPlayedRomId;
    }
}
