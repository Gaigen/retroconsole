package com.retroconsole.client.library;

import com.retroconsole.client.ClientLibraryCache;
import com.retroconsole.client.ModTexts;
import com.retroconsole.library.RomLibrary;

import java.nio.file.Path;
import java.util.Properties;

/** Per-player stats: playtime, launches, favorites (local file; playtime from server in MP). */
public final class PlayStats {

    private static final Properties P = new Properties();
    private static boolean loaded;
    private static Path loadedFile;

    private static synchronized Properties props() {
        Path file = ClientPlayerData.statsFile();
        if (!loaded || !file.equals(loadedFile)) {
            P.clear();
            RomLibrary.loadProps(P, file);
            loaded = true;
            loadedFile = file;
        }
        return P;
    }
    private static long getLong(String key) {
        try { return Long.parseLong(props().getProperty(key, "0")); }
        catch (NumberFormatException e) { return 0; }
    }
    private static void save() {
        RomLibrary.saveProps(props(), ClientPlayerData.statsFile(), "retroconsole play stats");
    }

    private static boolean useServer() {
        return ClientLibraryCache.useServerLibrary();
    }

    public static long playtimeSec(String romId) {
        return useServer() ? ClientLibraryCache.playtimeSec(romId)
                : getLong(romId + ".playtime");
    }

    public static int launches(String romId) {
        return useServer() ? ClientLibraryCache.launches(romId)
                : (int) getLong(romId + ".launches");
    }

    public static long lastPlayed(String romId) {
        return useServer() ? ClientLibraryCache.lastPlayed(romId)
                : getLong(romId + ".lastPlayed");
    }
    public static boolean favorite(String romId) { return "1".equals(props().getProperty(romId + ".fav")); }

    public static void toggleFavorite(String romId) {
        if (favorite(romId)) props().remove(romId + ".fav");
        else props().setProperty(romId + ".fav", "1");
        save();
    }

    public static void onLaunch(String romId) {
        if (useServer()) return;
        props().setProperty(romId + ".launches", String.valueOf(launches(romId) + 1));
        props().setProperty(romId + ".lastPlayed", String.valueOf(System.currentTimeMillis() / 1000));
        save();
    }

    public static void addPlaytime(String romId, long seconds) {
        if (useServer()) return;
        if (seconds <= 0) return;
        props().setProperty(romId + ".playtime", String.valueOf(playtimeSec(romId) + seconds));
        save();
    }

    /** Last launched rom id, or null. */
    public static String lastPlayedRomId() {
        if (useServer()) return ClientLibraryCache.lastPlayedRomId();
        String best = null;
        long bestT = 0;
        for (String key : props().stringPropertyNames())
            if (key.endsWith(".lastPlayed")) {
                long t = getLong(key);
                if (t > bestT) { bestT = t; best = key.substring(0, key.length() - 11); }
            }
        return best;
    }

    public static String formatPlaytime(long sec) {
        if (sec <= 0) return "";
        if (sec < 3600) return ModTexts.s("time.minutes", Math.max(1, sec / 60));
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h < 10 && m > 0
                ? ModTexts.s("time.hours_minutes", h, m)
                : ModTexts.s("time.hours", h);
    }

    public static String formatAgo(long epochSec) {
        long days = (System.currentTimeMillis() / 1000 - epochSec) / 86400;
        if (days <= 0) return ModTexts.s("time.today");
        if (days == 1) return ModTexts.s("time.yesterday");
        if (days < 30) return ModTexts.s("time.days_ago", days);
        return ModTexts.s("time.months_ago", days / 30);
    }
}