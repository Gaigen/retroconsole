package com.retroconsole.library;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.platform.RetroConsolePaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Реестр игровых систем: встроенные + config/retroconsole/systems.json
 * + автоматические «папка = вкладка» из подпапок roms/.
 */
public final class GameSystem {

    public final String id;
    public final String badge;
    public final String tab;
    public final String fullName;
    public final String folder;
    public final int color;
    public final List<String> exts;
    public final List<String> coreHints;
    public final boolean custom;

    private GameSystem(String id, String badge, String tab, String fullName, String folder,
                       int color, List<String> exts, List<String> coreHints, boolean custom) {
        this.id = id; this.badge = badge; this.tab = tab; this.fullName = fullName;
        this.folder = folder; this.color = color; this.exts = exts;
        this.coreHints = coreHints; this.custom = custom;
    }

    private static final List<GameSystem> REGISTRY = new ArrayList<>();
    public static GameSystem OTHER;

    private static final Map<String, String> FOLDER_ALIASES = Map.of(
            "psx", "ps1", "playstation", "ps1", "dc", "dreamcast",
            "md", "genesis", "megadrive", "genesis", "mastersystem", "sms");

    private static final int[] PALETTE = {0xFFE05050, 0xFF50B060, 0xFF4880D8, 0xFFD09040,
            0xFF9070E0, 0xFF38A8A0, 0xFFC85880, 0xFF88A040};

    private static Path configFile() {
        return RetroConsolePaths.romsDir().getParent().resolve("systems.json");
    }

    private static Path romsDir() {
        return RetroConsolePaths.romsDir();
    }

    public static synchronized List<GameSystem> all() {
        if (REGISTRY.isEmpty()) reload();
        return List.copyOf(REGISTRY);
    }

    public static synchronized void reload() {
        REGISTRY.clear();
        registerBuiltins();
        loadJson();
        finishRegistry();
    }

    /** Каталог с dedicated server: встроенные системы + метаданные с сервера (без client systems.json). */
    public static synchronized void applyServerCatalog(List<RetroLibraryPacket.SystemEntry> entries) {
        REGISTRY.clear();
        registerBuiltins();
        for (RetroLibraryPacket.SystemEntry e : entries) {
            if (isBuiltinId(e.id())) continue;
            REGISTRY.removeIf(s -> s.id.equalsIgnoreCase(e.id()));
            REGISTRY.add(fromEntry(e));
        }
        finishRegistry();
    }

    static GameSystem fromEntry(RetroLibraryPacket.SystemEntry e) {
        return new GameSystem(e.id(), e.badge(), e.tab(), e.fullName(), e.folder(),
                e.color(), e.exts(), e.coreHints(), e.custom());
    }

    public static RetroLibraryPacket.SystemEntry toEntry(GameSystem s) {
        return new RetroLibraryPacket.SystemEntry(s.id, s.badge, s.tab, s.fullName, s.folder,
                s.color, s.exts, s.coreHints, s.custom);
    }

    private static void registerBuiltins() {
        builtin("NES", "NES", "NES", "Nintendo NES", "nes", 0xFFE05050,
                List.of(".nes"), List.of("fceumm", "nestopia", "mesen", "quicknes"));
        builtin("SNES", "SNES", "SNES", "Super Nintendo", "snes", 0xFF9070E0,
                List.of(".sfc", ".smc"), List.of("snes9x", "bsnes"));
        builtin("GB", "GB", "Game Boy", "Game Boy / Color", "gb", 0xFF88C070,
                List.of(".gb", ".gbc"), List.of("gambatte", "sameboy"));
        builtin("GBA", "GBA", "GBA", "Game Boy Advance", "gba", 0xFF6878D8,
                List.of(".gba"), List.of("mgba", "vba"));
        builtin("GENESIS", "GEN", "Genesis", "Sega Genesis / Mega Drive", "genesis", 0xFF4880D8,
                List.of(".gen", ".md"), List.of("genesis_plus_gx", "picodrive"));
        builtin("SMS", "SMS", "Master System", "Sega Master System", "sms", 0xFF38A8A0,
                List.of(".sms", ".gg"), List.of("smsplus", "genesis_plus_gx", "picodrive"));
        builtin("PS1", "PS1", "PS1", "PlayStation", "ps1", 0xFF9098B8,
                List.of(), List.of("swanstation", "pcsx_rearmed", "beetle_psx", "mednafen_psx"));
        builtin("PS2", "PS2", "PS2", "PlayStation 2", "ps2", 0xFF3858C8,
                List.of(), List.of("pcsx2", "play_"));
        builtin("PSP", "PSP", "PSP", "PlayStation Portable", "psp", 0xFF48A0E0,
                List.of(".cso"), List.of("ppsspp"));
        builtin("DREAMCAST", "DC", "Dreamcast", "Sega Dreamcast", "dreamcast", 0xFFE08838,
                List.of(".cdi", ".gdi"), List.of("flycast"));
        builtin("SEGACD", "SCD", "Sega CD", "Sega CD / Mega CD", "segacd", 0xFF30B8C8,
                List.of(), List.of("genesis_plus_gx", "picodrive"));
        builtin("SATURN", "SAT", "Saturn", "Sega Saturn", "saturn", 0xFF7858B0,
                List.of(), List.of("mednafen_saturn", "beetle_saturn", "kronos", "yabause"));
    }

    private static void finishRegistry() {
        OTHER = new GameSystem("OTHER", "?", "Другое", "Неопознанные", "other",
                0xFF808080, List.of(), List.of(), false);
        REGISTRY.add(OTHER);
    }

    private static boolean isBuiltinId(String id) {
        if (id == null) return false;
        for (GameSystem s : REGISTRY) if (!s.custom && s.id.equalsIgnoreCase(id)) return true;
        return false;
    }

    private static void builtin(String id, String badge, String tab, String full, String folder,
                                int color, List<String> exts, List<String> hints) {
        REGISTRY.add(new GameSystem(id, badge, tab, full, folder, color, exts, hints, false));
    }

    private static void loadJson() {
        ensureJsonFile();
        Path config = configFile();
        if (!Files.exists(config)) return;
        try (Reader r = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            for (JsonElement el : JsonParser.parseReader(r).getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                String id = o.get("id").getAsString().toLowerCase(Locale.ROOT);
                if (byId(id) != null) continue;
                String name = o.has("name") ? o.get("name").getAsString() : id;
                String badge = o.has("badge") ? o.get("badge").getAsString()
                        : name.substring(0, Math.min(3, name.length())).toUpperCase(Locale.ROOT);
                String folder = o.has("folder") ? o.get("folder").getAsString() : id;
                int color = o.has("color")
                        ? 0xFF000000 | Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16)
                        : colorFromName(id);
                REGISTRY.add(new GameSystem(id, badge, name, name, folder, color,
                        strings(o, "exts"), strings(o, "cores"), true));
            }
        } catch (Exception ignored) {}
    }

    private static void ensureJsonFile() {
        Path config = configFile();
        if (Files.exists(config)) return;
        try {
            Files.createDirectories(config.getParent());
            JsonArray arr = new JsonArray();
            Set<String> seen = new HashSet<>();
            Path roms = romsDir();
            if (Files.isDirectory(roms)) {
                try (var stream = Files.newDirectoryStream(roms)) {
                    for (Path dir : stream) {
                        if (!Files.isDirectory(dir)) continue;
                        String folder = dir.getFileName().toString();
                        if (byFolder(folder) != null) continue;
                        String id = folder.toLowerCase(Locale.ROOT);
                        if (!seen.add(id)) continue;
                        arr.add(folderEntry(id, folder));
                    }
                }
            }
            if (arr.isEmpty()) arr.add(exampleEntry());
            writeJson(arr);
        } catch (Exception ignored) {}
    }

    private static void persistFolderSystem(GameSystem sys) {
        if (!sys.custom) return;
        try {
            Path config = configFile();
            JsonArray arr;
            if (Files.exists(config)) {
                try (Reader r = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                    arr = JsonParser.parseReader(r).getAsJsonArray();
                }
            } else {
                arr = new JsonArray();
            }
            for (JsonElement el : arr) {
                String id = el.getAsJsonObject().get("id").getAsString();
                if (id.equalsIgnoreCase(sys.id)) return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", sys.id);
            o.addProperty("name", sys.fullName);
            o.addProperty("badge", sys.badge);
            o.addProperty("folder", sys.folder);
            o.addProperty("color", String.format("#%06X", sys.color & 0xFFFFFF));
            arr.add(o);
            writeJson(arr);
        } catch (Exception ignored) {}
    }

    private static JsonObject folderEntry(String id, String folder) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        String tab = folder.substring(0, 1).toUpperCase(Locale.ROOT) + folder.substring(1);
        o.addProperty("name", tab);
        o.addProperty("badge", badgeFrom(folder));
        o.addProperty("folder", id);
        o.addProperty("color", String.format("#%06X", colorFromName(id) & 0xFFFFFF));
        return o;
    }

    private static JsonObject exampleEntry() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "atari2600");
        o.addProperty("name", "Atari 2600");
        o.addProperty("badge", "A26");
        o.addProperty("folder", "atari");
        o.addProperty("color", "#D08030");
        JsonArray exts = new JsonArray();
        exts.add(".a26");
        o.add("exts", exts);
        JsonArray cores = new JsonArray();
        cores.add("stella");
        o.add("cores", cores);
        return o;
    }

    private static String badgeFrom(String folder) {
        String badge = folder.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT);
        if (badge.isEmpty()) return "?";
        return badge.substring(0, Math.min(3, badge.length()));
    }

    private static void writeJson(JsonArray arr) throws java.io.IOException {
        try (Writer w = Files.newBufferedWriter(configFile(), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(arr, w);
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key))
            for (JsonElement e : o.getAsJsonArray(key))
                out.add(e.getAsString().toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }

    private static int colorFromName(String name) {
        return PALETTE[Math.abs(name.hashCode()) % PALETTE.length];
    }

    public static GameSystem byId(String id) {
        if (id == null) return null;
        for (GameSystem s : all()) if (s.id.equalsIgnoreCase(id)) return s;
        return null;
    }

    public static GameSystem byFolder(String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        name = FOLDER_ALIASES.getOrDefault(name, name);
        for (GameSystem s : all()) if (s.folder.equals(name) || s.id.equalsIgnoreCase(name)) return s;
        return null;
    }

    public static synchronized GameSystem forFolder(String rawFolder) {
        GameSystem existing = byFolder(rawFolder);
        if (existing != null) return existing;
        String folder = rawFolder.toLowerCase(Locale.ROOT);
        String tab = rawFolder.substring(0, 1).toUpperCase(Locale.ROOT) + rawFolder.substring(1);
        String badge = folder.replaceAll("[^a-z0-9]", "").toUpperCase(Locale.ROOT);
        badge = badge.isEmpty() ? "?" : badge.substring(0, Math.min(3, badge.length()));
        GameSystem sys = new GameSystem(folder, badge, tab, tab, folder,
                colorFromName(folder), List.of(), List.of(), true);
        REGISTRY.add(REGISTRY.indexOf(OTHER), sys);
        persistFolderSystem(sys);
        return sys;
    }

    public static GameSystem byExt(String ext) {
        GameSystem found = null;
        for (GameSystem s : all())
            if (s.exts.contains(ext)) {
                if (found != null) return null;
                found = s;
            }
        return found;
    }

    public static GameSystem byCore(String coreId) {
        String lower = coreId.toLowerCase(Locale.ROOT);
        for (GameSystem s : all())
            for (String hint : s.coreHints)
                if (lower.contains(hint)) return s;
        return OTHER;
    }
}
