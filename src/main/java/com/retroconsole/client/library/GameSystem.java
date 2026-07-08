package com.retroconsole.client.library;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    public final String id;          // стабильный ключ (кэши, настройки)
    public final String badge;       // короткий бейдж в строке (GEN)
    public final String tab;         // читаемое имя вкладки (Genesis)
    public final String fullName;    // полное имя (Sega Genesis / Mega Drive)
    public final String folder;      // подпапка в roms/
    public final int color;
    public final List<String> exts;      // однозначные расширения
    public final List<String> coreHints; // приоритетные ядра
    public final boolean custom;         // из json или из папки

    private GameSystem(String id, String badge, String tab, String fullName, String folder,
                       int color, List<String> exts, List<String> coreHints, boolean custom) {
        this.id = id; this.badge = badge; this.tab = tab; this.fullName = fullName;
        this.folder = folder; this.color = color; this.exts = exts;
        this.coreHints = coreHints; this.custom = custom;
    }

    // ---------- реестр ----------

    private static final Path CONFIG = Paths.get("config/retroconsole/systems.json");
    private static final Path ROMS_DIR = Paths.get("config/retroconsole/roms");
    private static final List<GameSystem> REGISTRY = new ArrayList<>();
    public static GameSystem OTHER;

    private static final Map<String, String> FOLDER_ALIASES = Map.of(
            "psx", "ps1", "playstation", "ps1", "dc", "dreamcast",
            "md", "genesis", "megadrive", "genesis", "mastersystem", "sms");

    private static final int[] PALETTE = {0xFFE05050, 0xFF50B060, 0xFF4880D8, 0xFFD09040,
            0xFF9070E0, 0xFF38A8A0, 0xFFC85880, 0xFF88A040};

    public static synchronized List<GameSystem> all() {
        if (REGISTRY.isEmpty()) reload();
        return List.copyOf(REGISTRY);
    }

    public static synchronized void reload() {
        REGISTRY.clear();
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
        loadJson();
        OTHER = new GameSystem("OTHER", "?", "Другое", "Неопознанные", "other",
                0xFF808080, List.of(), List.of(), false);
        REGISTRY.add(OTHER);   // «Другое» всегда последняя
    }

    private static void builtin(String id, String badge, String tab, String full, String folder,
                                int color, List<String> exts, List<String> hints) {
        REGISTRY.add(new GameSystem(id, badge, tab, full, folder, color, exts, hints, false));
    }

    /** systems.json: [{"id":"atari2600","name":"Atari 2600","badge":"A26",
     *  "exts":[".a26"],"cores":["stella"],"color":"#D08030"}] */
    private static void loadJson() {
        ensureJsonFile();
        if (!Files.exists(CONFIG)) return;
        try (Reader r = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
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

    /** Создаёт systems.json при первом запуске: подпапки roms/ + пример. */
    private static void ensureJsonFile() {
        if (Files.exists(CONFIG)) return;
        try {
            Files.createDirectories(CONFIG.getParent());
            JsonArray arr = new JsonArray();
            Set<String> seen = new HashSet<>();
            if (Files.isDirectory(ROMS_DIR)) {
                try (var stream = Files.newDirectoryStream(ROMS_DIR)) {
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

    /** Дописывает новую папочную систему в systems.json. */
    private static void persistFolderSystem(GameSystem sys) {
        if (!sys.custom) return;
        try {
            JsonArray arr;
            if (Files.exists(CONFIG)) {
                try (Reader r = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
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
        try (Writer w = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
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

    // ---------- поиск ----------

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

    /** Папка = вкладка: неизвестная подпапка roms/ становится своей системой. */
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

    /** Система по расширению — только если оно однозначно. */
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