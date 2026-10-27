package com.retroconsole.library;

import com.retroconsole.platform.RetroConsolePaths;
import com.retroconsole.platform.RetroPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RPG Maker run-time packages (RTP).
 *
 * <p>Freeware RPG Maker XP/VX/VX Ace games are frequently shipped without the
 * engine's run-time package and instead declare what they expect in
 * {@code Game.ini} ({@code RTP1=Standard}). mkxp-z mounts each declared package
 * from {@code <system>/mkxp-z/RTP/<name>} at {@code /Game}, so a game whose RTP
 * is not installed dies on the first asset it does not bundle itself — for
 * Knight Blade that is the title theme {@code Audio/BGM/012-Theme01} plus the
 * default system/victory sounds taken from {@code Data/System.rxdata}.
 */
public final class RpgMakerRtp {

    private RpgMakerRtp() {}

    /** {@code RTP}/{@code RTP1..RTP3} names from the game's Game.ini, in file order. */
    public static List<String> declared(Path content) {
        Path ini = gameIni(content);
        if (ini == null) return List.of();
        String text;
        try {
            // Game.ini is ANSI in every RPG Maker release; package names are ASCII.
            text = new String(Files.readAllBytes(ini), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            return List.of();
        }
        List<String> names = new ArrayList<>(3);
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            switch (line.substring(0, eq).strip().toUpperCase(Locale.ROOT)) {
                case "RTP", "RTP1", "RTP2", "RTP3" -> { }
                default -> { continue; }
            }
            String value = unquote(line.substring(eq + 1).strip());
            if (!value.isEmpty() && !names.contains(value)) names.add(value);
        }
        return List.copyOf(names);
    }

    /** Declared packages that are not installed; empty for non-RPG-Maker content. */
    public static List<String> missing(Path content) {
        List<String> out = new ArrayList<>(2);
        for (String name : declared(content)) {
            Path dir = installDir(name);
            if (dir == null || !Files.isDirectory(dir)) out.add(name);
        }
        return List.copyOf(out);
    }

    /**
     * Where mkxp-z mounts package {@code name}: {@code <system>/mkxp-z/RTP/<name>}
     * (see {@code core.cpp} — it creates that folder on startup and refuses RTPs
     * from outside the libretro system directory). Null when the name is unusable.
     */
    public static Path installDir(String name) {
        String clean = sanitize(name);
        if (clean == null) return null;
        try {
            return RetroConsolePaths.systemDir().resolve("mkxp-z").resolve("RTP").resolve(clean);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Player-facing form of {@link #installDir(String)}: relative to the game
     * directory when it lives there, so the chat line is short and copyable.
     */
    public static String installHint(String name) {
        Path dir = installDir(name);
        if (dir == null) return "system/mkxp-z/RTP/" + name;
        String abs = dir.toString().replace('\\', '/');
        try {
            String gameDir = RetroPaths.get().gameDir().toString().replace('\\', '/');
            if (!gameDir.isEmpty() && abs.startsWith(gameDir + "/")) {
                return abs.substring(gameDir.length() + 1);
            }
        } catch (Exception ignored) {}
        return abs;
    }

    /** Game.ini next to the content: the file itself, or inside the game folder. */
    private static Path gameIni(Path content) {
        if (content == null) return null;
        Path dir = Files.isDirectory(content) ? content : content.getParent();
        if (dir == null) return null;
        Path ini = dir.resolve("Game.ini");
        return Files.isRegularFile(ini) ? ini : null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
            return value.substring(1, value.length() - 1).strip();
        return value;
    }

    /** Package names become one path segment — refuse anything that could escape RTP/. */
    private static String sanitize(String name) {
        if (name == null) return null;
        String clean = name.strip();
        while (clean.startsWith("/") || clean.startsWith("\\")) clean = clean.substring(1);
        if (clean.isEmpty() || clean.contains("..")
                || clean.indexOf('/') >= 0 || clean.indexOf('\\') >= 0
                || clean.indexOf(':') >= 0) {
            return null;
        }
        return clean;
    }
}
