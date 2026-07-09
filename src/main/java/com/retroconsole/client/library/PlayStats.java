package com.retroconsole.client.library;

import com.retroconsole.library.RomLibrary;

import java.nio.file.Path;
import java.util.Properties;

/** Личная статистика игрока: наиграно, запуски, избранное. Хранится локально у клиента. */
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

    public static long playtimeSec(String romId) { return getLong(romId + ".playtime"); }
    public static int launches(String romId)     { return (int) getLong(romId + ".launches"); }
    public static long lastPlayed(String romId)  { return getLong(romId + ".lastPlayed"); }
    public static boolean favorite(String romId) { return "1".equals(props().getProperty(romId + ".fav")); }

    public static void toggleFavorite(String romId) {
        if (favorite(romId)) props().remove(romId + ".fav");
        else props().setProperty(romId + ".fav", "1");
        save();
    }

    public static void onLaunch(String romId) {
        props().setProperty(romId + ".launches", String.valueOf(launches(romId) + 1));
        props().setProperty(romId + ".lastPlayed", String.valueOf(System.currentTimeMillis() / 1000));
        save();
    }

    public static void addPlaytime(String romId, long seconds) {
        if (seconds <= 0) return;
        props().setProperty(romId + ".playtime", String.valueOf(playtimeSec(romId) + seconds));
        save();
    }

    /** id последней запускавшейся игры или null. */
    public static String lastPlayedRomId() {
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
        if (sec < 3600) return Math.max(1, sec / 60) + " мин";
        long h = sec / 3600, m = (sec % 3600) / 60;
        return h < 10 && m > 0 ? h + " ч " + m + " мин" : h + " ч";
    }

    public static String formatAgo(long epochSec) {
        long days = (System.currentTimeMillis() / 1000 - epochSec) / 86400;
        if (days <= 0) return "сегодня";
        if (days == 1) return "вчера";
        if (days < 30) return days + " дн. назад";
        return (days / 30) + " мес. назад";
    }
}