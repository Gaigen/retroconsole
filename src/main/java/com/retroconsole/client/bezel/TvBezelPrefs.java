package com.retroconsole.client.bezel;

import com.retroconsole.client.library.ClientPlayerData;
import com.retroconsole.library.RomLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/** Client-local letterbox art settings. Stored per player UUID. */
public final class TvBezelPrefs {

    public enum FitMode { FILL, FIT, STRETCH }

    private static final Set<String> ART_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    /** Sentinel for "no image" in pickers. */
    public static final String NONE = "";
    private static final String FILE = "bezel.properties";
    private static final Properties P = new Properties();
    private static boolean loaded;
    private static Path loadedFile;

    private TvBezelPrefs() {}

    public static Path bezelDir() {
        Path dir = ClientPlayerData.dir().resolve("bezel");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
        return dir;
    }

    /** @deprecated use {@link #bezelDir()} which now auto-creates the folder */
    @Deprecated
    public static void ensureDir() {
        bezelDir();
    }

    /** Filenames inside {@link #bezelDir()} plus {@link #NONE} as the first entry. */
    public static List<String> listArtOptions() {
        ensureDir();
        List<String> names = new ArrayList<>();
        names.add(NONE);
        try (Stream<Path> stream = Files.list(bezelDir())) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(TvBezelPrefs::isArtFile)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(names::add);
        } catch (IOException ignored) {}
        return names;
    }

    public static boolean isArtFile(String filename) {
        if (filename == null || filename.isBlank()) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return false;
        return ART_EXT.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static String fileNameForSide(Path path) {
        if (path == null) return NONE;
        Path bez = bezelDir().toAbsolutePath().normalize();
        Path abs = path.toAbsolutePath().normalize();
        if (abs.startsWith(bez)) return bez.relativize(abs).toString().replace('\\', '/');
        return path.getFileName().toString();
    }

    public static void setLeftFile(String name) {
        setLeftPath(resolveArtName(name));
    }

    public static void setRightFile(String name) {
        setRightPath(resolveArtName(name));
    }

    private static Path resolveArtName(String name) {
        if (name == null || name.isBlank()) return null;
        Path rel = bezelDir().resolve(name).normalize();
        return Files.isRegularFile(rel) ? rel : null;
    }

    private static synchronized Properties props() {
        Path file = ClientPlayerData.dir().resolve(FILE);
        if (!loaded || !file.equals(loadedFile)) {
            P.clear();
            RomLibrary.loadProps(P, file);
            loaded = true;
            loadedFile = file;
        }
        return P;
    }

    private static synchronized void save() {
        RomLibrary.saveProps(props(), ClientPlayerData.dir().resolve(FILE), "tv bezel prefs");
    }

    public static synchronized boolean enabled() {
        return Boolean.parseBoolean(props().getProperty("enabled", "true"));
    }

    public static synchronized void setEnabled(boolean v) {
        props().setProperty("enabled", Boolean.toString(v));
        save();
    }

    public static synchronized float opacity() {
        try {
            float v = Float.parseFloat(props().getProperty("opacity", "1.0"));
            return Math.max(0f, Math.min(1f, v));
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    public static synchronized void setOpacity(float v) {
        props().setProperty("opacity", String.format(Locale.ROOT, "%.3f", Math.max(0f, Math.min(1f, v))));
        save();
    }

    public static synchronized FitMode fitMode() {
        try {
            return FitMode.valueOf(props().getProperty("fit", "FIT").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FitMode.FIT;
        }
    }

    public static synchronized void setFitMode(FitMode mode) {
        props().setProperty("fit", mode.name());
        save();
    }

    public static synchronized Path leftPath() {
        return resolvePath(props().getProperty("left", ""));
    }

    public static synchronized Path rightPath() {
        return resolvePath(props().getProperty("right", ""));
    }

    public static synchronized void setLeftPath(Path p) {
        props().setProperty("left", relativizeOrAbsolute(p));
        save();
    }

    public static synchronized void setRightPath(Path p) {
        props().setProperty("right", relativizeOrAbsolute(p));
        save();
    }

    private static String relativizeOrAbsolute(Path p) {
        if (p == null) return "";
        Path bez = bezelDir().toAbsolutePath().normalize();
        Path abs = p.toAbsolutePath().normalize();
        if (abs.startsWith(bez)) return bez.relativize(abs).toString().replace('\\', '/');
        return abs.toString();
    }

    private static Path resolvePath(String stored) {
        if (stored == null || stored.isBlank()) return null;
        Path p = Path.of(stored);
        if (p.isAbsolute()) return Files.isRegularFile(p) ? p : null;
        Path rel = bezelDir().resolve(stored).normalize();
        return Files.isRegularFile(rel) ? rel : null;
    }
}
