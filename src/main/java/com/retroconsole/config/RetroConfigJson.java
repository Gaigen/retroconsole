package com.retroconsole.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.retroconsole.platform.RetroPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's one config file, {@code config/retroconsole.json}, shared by every
 * loader.
 *
 * <p>The tree mirrors the old NeoForge toml sections — {@code paths},
 * {@code streaming}, {@code limits}, {@code video}, {@code input} — and doubles
 * as a {@link RetroSettings.Source}: once handed to {@link #install(RetroConfigJson)},
 * the rest of the mod reads it through {@link RetroSettings} and never touches JSON.
 *
 * <p>Field initialisers are the defaults, and Gson runs them for keys missing
 * from the file (it uses the no-arg constructor when one exists), so a hand
 * edited or truncated file keeps working. {@link #normalise()} additionally
 * repairs sections that were set to {@code null} and clamps values to the
 * ranges the old config spec enforced.
 *
 * <p>{@link #legacyTomlImported} records whether the one-time import of the old
 * NeoForge toml files is finished. The server section only becomes readable
 * when the server config loads (world start), so the import completes lazily
 * and this flag is what tells the next launch whether the legacy specs still
 * have to be registered — see {@code platform/NeoForgeConfig}.
 */
public final class RetroConfigJson implements RetroSettings.Source {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConfig");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String RELATIVE_PATH = "config/retroconsole.json";

    private static final RetroConfigJson EMPTY = new RetroConfigJson();

    private static volatile RetroConfigJson current = EMPTY;

    public Paths paths = new Paths();
    public Streaming streaming = new Streaming();
    public Limits limits = new Limits();
    public Video video = new Video();
    public Input input = new Input();

    /** Set once every legacy toml section has been copied into this file. */
    public boolean legacyTomlImported = false;

    /** Keep: Gson needs it so the field initialisers above act as defaults. */
    public RetroConfigJson() {}

    // ----- file handling ---------------------------------------------------

    public static Path file() {
        return RetroPaths.get().gameDir().resolve(RELATIVE_PATH);
    }

    public static boolean exists() {
        return Files.isRegularFile(file());
    }

    /** The installed instance (live: the config screen edits it in place). */
    public static RetroConfigJson get() {
        return current;
    }

    /** Snapshot another source into a fresh tree — used by the toml import. */
    public static RetroConfigJson from(RetroSettings.Source src) {
        RetroConfigJson cfg = new RetroConfigJson();
        cfg.copyPaths(src);
        cfg.copyStreaming(src);
        cfg.copyLimits(src);
        cfg.copyVideo(src);
        cfg.copyInput(src);
        cfg.normalise();
        return cfg;
    }

    // Section copies: the legacy values live in three toml files that load at
    // different times, so the import fills the tree one section group at a time.

    /** {@code paths} — the legacy {@code common} config. */
    public void copyPaths(RetroSettings.Source src) {
        paths.coresDir = src.coresPath();
        paths.romsDir = src.romsPath();
        paths.systemDir = src.systemPath();
        paths.saveDir = src.savePath();
        paths.artDir = src.artPath();
    }

    /** {@code streaming} — part of the legacy {@code server} config. */
    public void copyStreaming(RetroSettings.Source src) {
        streaming.videoDistance = src.videoDistance();
        streaming.audioDistance = src.audioDistance();
        streaming.viewSubscribeDistance = src.viewSubscribeDistance();
        streaming.controlDistance = src.controlDistance();
        streaming.notifyDistance = src.notifyDistance();
        streaming.worldMaxWidth = src.worldMaxWidth();
    }

    /** {@code limits} — part of the legacy {@code server} config. */
    public void copyLimits(RetroSettings.Source src) {
        limits.maxCoreSlots = src.maxCoreSlots();
        limits.maxPcsx2Sessions = src.maxPcsx2Sessions();
        limits.maxScreenCluster = src.maxScreenCluster();
        limits.batteryAutosaveSeconds = src.batteryAutosaveSeconds();
        limits.enable3ds = src.enable3ds();
    }

    /** {@code video} — part of the legacy {@code server} config. */
    public void copyVideo(RetroSettings.Source src) {
        video.preset = src.videoPreset();
        video.flycastInternalResolution = src.flycastInternalResolution();
        video.flycastTexUpscale = src.flycastTexUpscale();
        video.flycastAnisotropic = src.flycastAnisotropic();
        video.ppssppInternalResolution = src.ppssppInternalResolution();
        video.ppssppTextureScaling = src.ppssppTextureScaling();
        video.pcsx2UpscaleMultiplier = src.pcsx2UpscaleMultiplier();
        video.pcsx2Anisotropic = src.pcsx2Anisotropic();
        video.citraResolution = src.citraResolution();
        video.doom3Resolution = src.doom3Resolution();
        video.doom3InvertY = src.doom3InvertY();
    }

    /** {@code input} — the legacy {@code client} config. */
    public void copyInput(RetroSettings.Source src) {
        input.gamepadEnabled = src.gamepadEnabled();
        input.gamepadDeadzone = src.gamepadDeadzone();
        input.gamepadTriggerThreshold = src.gamepadTriggerThreshold();
    }

    /** Whether the file on disk has already absorbed every legacy toml section. */
    public static boolean legacyImportDone() {
        Path f = file();
        if (!Files.isRegularFile(f)) return false;
        try (Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            RetroConfigJson cfg = GSON.fromJson(reader, RetroConfigJson.class);
            return cfg != null && cfg.legacyTomlImported;
        } catch (IOException | JsonParseException e) {
            return false;
        }
    }

    /** Read the file (defaults when missing or unreadable) and install it. */
    public static synchronized RetroConfigJson load() {
        Path f = file();
        RetroConfigJson cfg = null;
        if (Files.isRegularFile(f)) {
            try (Reader reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                cfg = GSON.fromJson(reader, RetroConfigJson.class);
            } catch (IOException | JsonParseException e) {
                LOGGER.error("Cannot read {}: {} — using defaults", f, e.getMessage());
            }
        }
        if (cfg == null) {
            cfg = from(EMPTY);
        }
        cfg.normalise();
        install(cfg);
        return cfg;
    }

    /** Make {@code cfg} the source every {@link RetroSettings} call reads. */
    public static void install(RetroConfigJson cfg) {
        RetroConfigJson target = cfg != null ? cfg : from(EMPTY);
        current = target;
        RetroSettings.setSource(target);
    }

    public void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer writer = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
                writer.write(System.lineSeparator());
            }
            LOGGER.info("Wrote {}", f);
        } catch (IOException e) {
            LOGGER.error("Cannot write {}: {}", f, e.getMessage());
        }
    }

    /** Repair a hand-edited tree: missing sections and null strings. */
    private void normalise() {
        if (paths == null) paths = new Paths();
        if (streaming == null) streaming = new Streaming();
        if (limits == null) limits = new Limits();
        if (video == null) video = new Video();
        if (input == null) input = new Input();

        paths.coresDir = or(paths.coresDir, EMPTY.paths.coresDir);
        paths.romsDir = or(paths.romsDir, EMPTY.paths.romsDir);
        paths.systemDir = or(paths.systemDir, EMPTY.paths.systemDir);
        paths.saveDir = or(paths.saveDir, EMPTY.paths.saveDir);
        paths.artDir = or(paths.artDir, EMPTY.paths.artDir);

        video.preset = or(video.preset, EMPTY.video.preset);
        video.flycastInternalResolution = or(video.flycastInternalResolution, "");
        video.flycastTexUpscale = or(video.flycastTexUpscale, "");
        video.flycastAnisotropic = or(video.flycastAnisotropic, "");
        video.ppssppInternalResolution = or(video.ppssppInternalResolution, "");
        video.ppssppTextureScaling = or(video.ppssppTextureScaling, "");
        video.pcsx2UpscaleMultiplier = or(video.pcsx2UpscaleMultiplier, "");
        video.pcsx2Anisotropic = or(video.pcsx2Anisotropic, "");
        video.citraResolution = or(video.citraResolution, "");
        video.doom3Resolution = or(video.doom3Resolution, "");
    }

    // ----- RetroSettings.Source --------------------------------------------

    @Override
    public String coresPath() {
        return paths.coresDir;
    }

    @Override
    public String romsPath() {
        return paths.romsDir;
    }

    @Override
    public String systemPath() {
        return paths.systemDir;
    }

    @Override
    public String savePath() {
        return paths.saveDir;
    }

    @Override
    public String artPath() {
        return paths.artDir;
    }

    @Override
    public int videoDistance() {
        return clampi(streaming.videoDistance, 8, 256);
    }

    @Override
    public int audioDistance() {
        return clampi(streaming.audioDistance, 4, 256);
    }

    @Override
    public int viewSubscribeDistance() {
        return clampi(streaming.viewSubscribeDistance, 8, 256);
    }

    @Override
    public int controlDistance() {
        return clampi(streaming.controlDistance, 2, 64);
    }

    @Override
    public int notifyDistance() {
        return clampi(streaming.notifyDistance, 32, 512);
    }

    @Override
    public int worldMaxWidth() {
        return clampi(streaming.worldMaxWidth, 160, 1920);
    }

    @Override
    public int maxCoreSlots() {
        return clampi(limits.maxCoreSlots, 0, 64);
    }

    @Override
    public int maxPcsx2Sessions() {
        return clampi(limits.maxPcsx2Sessions, 1, 8);
    }

    @Override
    public int maxScreenCluster() {
        return clampi(limits.maxScreenCluster, 4, 4096);
    }

    @Override
    public int batteryAutosaveSeconds() {
        return clampi(limits.batteryAutosaveSeconds, 5, 600);
    }

    @Override
    public boolean enable3ds() {
        return limits.enable3ds;
    }

    @Override
    public String videoPreset() {
        return video.preset;
    }

    @Override
    public String flycastInternalResolution() {
        return video.flycastInternalResolution;
    }

    @Override
    public String flycastTexUpscale() {
        return video.flycastTexUpscale;
    }

    @Override
    public String flycastAnisotropic() {
        return video.flycastAnisotropic;
    }

    @Override
    public String ppssppInternalResolution() {
        return video.ppssppInternalResolution;
    }

    @Override
    public String ppssppTextureScaling() {
        return video.ppssppTextureScaling;
    }

    @Override
    public String pcsx2UpscaleMultiplier() {
        return video.pcsx2UpscaleMultiplier;
    }

    @Override
    public String pcsx2Anisotropic() {
        return video.pcsx2Anisotropic;
    }

    @Override
    public String citraResolution() {
        return video.citraResolution;
    }

    @Override
    public String doom3Resolution() {
        return video.doom3Resolution;
    }

    @Override
    public boolean doom3InvertY() {
        return video.doom3InvertY;
    }

    @Override
    public boolean gamepadEnabled() {
        return input.gamepadEnabled;
    }

    @Override
    public double gamepadDeadzone() {
        return clampd(input.gamepadDeadzone, 0.0, 0.9);
    }

    @Override
    public double gamepadTriggerThreshold() {
        return clampd(input.gamepadTriggerThreshold, 0.05, 0.95);
    }

    // ----- helpers ---------------------------------------------------------

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int clampi(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampd(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ----- sections (public fields: Gson binds by name) --------------------

    public static final class Paths {
        public String coresDir = "config/retroconsole/cores";
        public String romsDir = "config/retroconsole/roms";
        public String systemDir = "config/retroconsole/system";
        public String saveDir = "config/retroconsole/saves";
        public String artDir = "config/retroconsole/art";
    }

    public static final class Streaming {
        public int videoDistance = 48;
        public int audioDistance = 32;
        public int viewSubscribeDistance = 64;
        public int controlDistance = 8;
        public int notifyDistance = 256;
        public int worldMaxWidth = 480;
    }

    public static final class Limits {
        public int maxCoreSlots = 0;
        public int maxPcsx2Sessions = 8;
        public int maxScreenCluster = 1024;
        public int batteryAutosaveSeconds = 30;
        public boolean enable3ds = true;
    }

    public static final class Video {
        public String preset = "balanced";
        public String flycastInternalResolution = "";
        public String flycastTexUpscale = "";
        public String flycastAnisotropic = "";
        public String ppssppInternalResolution = "";
        public String ppssppTextureScaling = "";
        public String pcsx2UpscaleMultiplier = "";
        public String pcsx2Anisotropic = "";
        public String citraResolution = "";
        public String doom3Resolution = "";
        public boolean doom3InvertY = false;
    }

    public static final class Input {
        public boolean gamepadEnabled = true;
        public double gamepadDeadzone = 0.15;
        public double gamepadTriggerThreshold = 0.5;
    }
}
