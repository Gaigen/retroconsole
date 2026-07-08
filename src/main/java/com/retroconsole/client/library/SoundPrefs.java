package com.retroconsole.client.library;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Звуковые настройки игрока (пока только громкость консолей).
 * Хранится в players/<uuid>/sound.properties.
 *
 * ВАЖНО: отдельный файл, а не ui.properties — CoreSelectScreen.savePrefs()
 * пересобирает ui.properties с нуля и затёр бы чужие ключи.
 */
public final class SoundPrefs {

    private static final String FILE_NAME = "sound.properties";
    private static final Properties P = new Properties();
    private static boolean loaded;
    private static Path loadedFile;

    private SoundPrefs() {}

    private static Path file() {
        return ClientPlayerData.dir().resolve(FILE_NAME);
    }

    private static synchronized Properties props() {
        Path file = file();
        if (!loaded || !file.equals(loadedFile)) { // перечитываем при смене игрока (см. PlayStats)
            P.clear();
            RomLibrary.loadProps(P, file);
            loaded = true;
            loadedFile = file;
        }
        return P;
    }

    /** Громкость консолей 0..1. По умолчанию 1.0. */
    public static synchronized float volume() {
        try {
            float v = Float.parseFloat(props().getProperty("volume", "1.0"));
            return Math.max(0f, Math.min(1f, v));
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    public static synchronized void setVolume(float v) {
        float clamped = Math.max(0f, Math.min(1f, v));
        props().setProperty("volume", String.format(Locale.ROOT, "%.3f", clamped));
        RomLibrary.saveProps(props(), file(), "retroconsole sound prefs");
    }
}