package com.retroconsole.bridge;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Windows implementation of {@link LibretroCore}. Built up step by step:
 *
 * <p><b>Step 1</b> (this commit): JNA-load the libretro {@code .dll} and
 * verify it answers to {@code retro_api_version()}. Nothing else works
 * yet — callbacks are not wired, {@link #loadGame(Path)} returns false.
 *
 * <p>Subsequent steps will add environment callbacks, frame delivery and
 * input. The Linux implementation lives in {@link LibretroCoreLinux} and
 * is intentionally not consulted during development of this file — we
 * build from libretro.h semantics, not by duplicating.
 */
public class LibretroCoreWindows extends LibretroCore {
    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroCoreWindows");

    /** The loaded libretro core. Null until {@link #load(Path, String, String)}
     *  reaches Step 1 and only ever non-null afterwards. */
    private LibretroBridge core;

    /** System / save directories to hand to the core on load. */
    private final String systemDir;
    private final String saveDir;

    public LibretroCoreWindows(Path corePath, String systemDir, String saveDir) {
        super(corePath);
        this.systemDir = systemDir;
        this.saveDir = saveDir;
        LOGGER.info("Windows libretro core stub created for {} (system={}, save={})",
                corePath, systemDir, saveDir);
    }

    // ----- Step 1: actually load the .dll ----------------------------------

    /**
     * Replace the stub construction with a real JNA load and run the core's
     * {@code retro_init()}. Calling it twice is a no-op.
     *
     * <p><b>Step 2</b>: registers a minimal environment callback and calls
     * {@code retro_init}. The callback answers {@code GET_CAN_DUPE=true}
     * (allow frame repeat) and refuses everything else — enough to get most
     * cores through initialisation. Frames, audio and input are still
     * stubbed.
     */
    void loadNative() {
        if (core != null) return;
        if (corePath == null) {
            LOGGER.error("Cannot load Windows libretro core: corePath is null");
            return;
        }
        String absPath = corePath.toAbsolutePath().toString();
        LOGGER.info("Native.load({})", absPath);
        try {
            this.core = Native.load(absPath, LibretroBridge.class);
            int apiVersion = core.retro_api_version();
            LOGGER.info("Core loaded. API version: {}", apiVersion);

            LOGGER.info("setupCallbacks()");
            setupCallbacks();

            LOGGER.info("retro_init()");
            core.retro_init();
            LOGGER.info("Core initialized.");
        } catch (Throwable t) {
            LOGGER.error("Failed to load libretro core at {}: {}", absPath, t.getMessage(), t);
            this.core = null;
        }
    }

    /** Register the environment callback with the core. Step 2 implements
     *  just enough of {@code retro_environment} for {@code retro_init()} to
     *  succeed. */
    private void setupCallbacks() {
        LibretroBridge.RetroEnvironment env = (cmd, data) -> handleEnvironment(cmd, data);
        // Hold a strong reference so JNA doesn't let the trampoline be GC'd.
        this.envCallback = env;
        core.retro_set_environment(env);
    }

    /** Strong reference kept so JNA doesn't GC the callback trampoline. */
    private LibretroBridge.RetroEnvironment envCallback;

    /**
     * Minimal environment callback. Step 2 only handles {@code GET_CAN_DUPE}
     * (allow frame repeat, almost every core expects yes). Other commands
     * are answered with {@code false} — the core may complain in its own log
     * but should not crash. Later steps expand this to system/save
     * directories, pixel format, log interface, etc.
     */
    private boolean handleEnvironment(int cmd, Pointer data) {
        if (cmd == LibretroEnvironment.GET_CAN_DUPE) {
            // data is a pointer to a single byte; write 1 (true).
            if (data != null) data.setByte(0, (byte) 1);
            return true;
        }
        // Everything else: refuse. This is a step-2 stub.
        return false;
    }

    /** Test helper / future API surface — exposes whether the core is
     *  currently loaded without leaking JNA types. */
    public boolean isCoreLoaded() {
        return core != null;
    }

    // ----- Step 3: actually give the core a ROM ----------------------------

    /** Width × height of the most recent game (set by {@link #loadGame}). */
    private volatile int width;
    private volatile int height;
    /** True after a successful retro_load_game. */
    private boolean gameLoaded;

    /**
     * Load a ROM. Builds a {@code retro_game_info}, hands it to the core via
     * {@code retro_load_game}, registers a joystick on port 0, queries
     * {@code retro_system_av_info} for the initial resolution. No HW-render
     * release — Windows does not run HW contexts.
     */
    @Override
    public boolean loadGame(Path romPath) {
        if (core == null) {
            LOGGER.warn("loadGame({}) called before loadNative() — ignoring.", romPath);
            return false;
        }
        LOGGER.info("loadGame({})", romPath);

        LibretroBridge.RetroGameInfo info;
        try {
            info = buildGameInfo(romPath);
        } catch (Exception e) {
            LOGGER.error("Failed to build game info for {}: {}", romPath, e.getMessage(), e);
            return false;
        }

        boolean ok;
        try {
            ok = core.retro_load_game(info);
        } catch (Throwable t) {
            LOGGER.error("retro_load_game crashed for {}: {}", romPath, t.getMessage(), t);
            freeGameInfo(info);
            return false;
        }

        if (!ok) {
            LOGGER.error("Core rejected ROM: {}", romPath.getFileName());
            freeGameInfo(info);
            return false;
        }
        this.loadedGameInfo = info; // keep memory alive for the lifetime of the game

        // Many cores require this to be set BEFORE retro_load_game. We do it
        // afterwards too — FCEUmm / nestopia don't care, others (like
        // mednafen_psx) may want the controller type early. Either way it
        // doesn't hurt.
        core.retro_set_controller_port_device(0, LibretroBridge.RETRO_DEVICE_JOYPAD);
        LOGGER.info("Controller registered: port 0 = JOYPAD");

        var avInfo = new LibretroBridge.RetroSystemAVInfo();
        core.retro_get_system_av_info(avInfo);
        this.width = avInfo.geometry.base_width;
        this.height = avInfo.geometry.base_height;
        this.gameLoaded = true;

        LOGGER.info("Game loaded: {} ({}x{}, FPS={}, sampleRate={})",
                romPath.getFileName(), width, height,
                avInfo.timing_fps, avInfo.timing_sample_rate);
        return true;
    }

    /** Kept referenced so JNA doesn't free the Memory holding small ROM bytes. */
    private LibretroBridge.RetroGameInfo loadedGameInfo;

    @Override public int getWidth()  { return width; }
    @Override public int getHeight() { return height; }

    /**
     * Build a {@code RetroGameInfo} for {@code romPath}. If the file is
     * larger than {@link #MAX_IN_MEMORY_ROM} or looks like a disc image, only
     * the path is handed to the core — it opens the file itself.
     */
    private LibretroBridge.RetroGameInfo buildGameInfo(Path romPath) throws java.io.IOException {
        var info = new LibretroBridge.RetroGameInfo();
        info.path = romPath.toAbsolutePath().toString();
        info.meta = "";

        String lower = romPath.toString().toLowerCase();
        boolean discImage = lower.endsWith(".cue") || lower.endsWith(".gdi")
                || lower.endsWith(".iso") || lower.endsWith(".chd")
                || lower.endsWith(".cso") || lower.endsWith(".mdf")
                || lower.endsWith(".nrg") || lower.endsWith(".img");
        long size = java.nio.file.Files.size(romPath);

        if (discImage || size > MAX_IN_MEMORY_ROM) {
            LOGGER.info("Disc/large image — passing path only: {}", romPath.getFileName());
            info.data = null;
            info.size = 0;
        } else {
            byte[] bytes = java.nio.file.Files.readAllBytes(romPath);
            var mem = new com.sun.jna.Memory(bytes.length);
            mem.write(0, bytes, 0, bytes.length);
            info.data = mem;
            info.size = bytes.length;
        }
        info.write(); // marshal fields into native struct memory
        return info;
    }

    /** Best-effort free of any Memory we allocated in {@link #buildGameInfo}. */
    private void freeGameInfo(LibretroBridge.RetroGameInfo info) {
        if (info != null && info.data != null) {
            try { /* JNA Memory is GC'd */ } catch (Exception ignored) {}
        }
        this.loadedGameInfo = null;
    }

    /** Same threshold Linux-impl uses (cartridge-scale; >= this → path only). */
    private static final long MAX_IN_MEMORY_ROM = 64L * 1024 * 1024;

    @Override public void runFrame() { /* no emulator running — Step 5 */ }

    @Override public boolean pollFrame(int[] dst) { return false; /* no frames yet — Step 4 */ }

    @Override public void setButton(int buttonId, boolean pressed) { /* no-op — Step 6 */ }

    @Override public void setAnalog(int stick, int axis, short value) { /* no-op — Step 6 */ }

    @Override public void reset() { /* no-op */ }

    @Override public byte[] serialize() { return null; }

    @Override public boolean unserialize(byte[] data) { return false; }

    @Override public byte[] getSaveRam() { return null; }

    @Override public void setSaveRam(byte[] sram) { /* no-op */ }

    @Override
    public void close() throws Exception {
        // Real unload happens in Step 7+. For now nothing is open that
        // requires explicit teardown beyond the JNA handle we let GC clean up.
    }

    public String getSystemDir() { return systemDir; }
    public String getSaveDir() { return saveDir; }
}
