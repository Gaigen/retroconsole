package com.retroconsole.server;

import com.retroconsole.library.RomLibrary;
import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.platform.RetroConsolePaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Server-side playtime stats (emulator runs on the server). */
public final class ServerPlayStats {

    private ServerPlayStats() {}

    private static Path fileFor(UUID playerId) {
        return RetroConsolePaths.playersSaveDir()
                .resolve(playerId.toString())
                .resolve("playstats.properties");
    }

    private static Properties load(UUID playerId) {
        Properties p = new Properties();
        RomLibrary.loadProps(p, fileFor(playerId));
        return p;
    }

    private static void save(UUID playerId, Properties p) {
        RomLibrary.saveProps(p, fileFor(playerId), "retroconsole server play stats");
    }

    public static void onLaunch(UUID playerId, String romId) {
        if (playerId == null || romId == null || romId.isEmpty()) return;
        Properties p = load(playerId);
        int launches = parseInt(p.getProperty(romId + ".launches", "0")) + 1;
        p.setProperty(romId + ".launches", String.valueOf(launches));
        p.setProperty(romId + ".lastPlayed", String.valueOf(System.currentTimeMillis() / 1000));
        save(playerId, p);
    }

    public static void addPlaytime(UUID playerId, String romId, long seconds) {
        if (playerId == null || romId == null || romId.isEmpty() || seconds <= 0) return;
        Properties p = load(playerId);
        long prev = parseLong(p.getProperty(romId + ".playtime", "0"));
        p.setProperty(romId + ".playtime", String.valueOf(prev + seconds));
        save(playerId, p);
    }

    public static List<RetroLibraryPacket.StatEntry> exportFor(UUID playerId) {
        if (playerId == null) return List.of();
        Properties p = load(playerId);
        List<RetroLibraryPacket.StatEntry> out = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.endsWith(".playtime")) continue;
            String romId = key.substring(0, key.length() - 9);
            long playtime = parseLong(p.getProperty(key, "0"));
            int launches = parseInt(p.getProperty(romId + ".launches", "0"));
            long lastPlayed = parseLong(p.getProperty(romId + ".lastPlayed", "0"));
            if (playtime > 0 || launches > 0) {
                out.add(new RetroLibraryPacket.StatEntry(romId, playtime, launches, lastPlayed));
            }
        }
        return out;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
