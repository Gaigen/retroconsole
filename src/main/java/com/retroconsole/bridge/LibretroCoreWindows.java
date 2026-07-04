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

    // ----- Stub implementations (Steps 2..N will replace these) -----------

    @Override
    public boolean loadGame(Path romPath) {
        LOGGER.warn("loadGame({}) called but Windows emulator support is not yet implemented.",
                romPath);
        return false;
    }

    @Override public void runFrame() { /* no emulator running */ }

    @Override public boolean pollFrame(int[] dst) { return false; }

    @Override public int getWidth() { return 0; }

    @Override public int getHeight() { return 0; }

    @Override public void setButton(int buttonId, boolean pressed) { /* no-op */ }

    @Override public void setAnalog(int stick, int axis, short value) { /* no-op */ }

    @Override public void reset() { /* no-op */ }

    @Override public byte[] serialize() { return null; }

    @Override public boolean unserialize(byte[] data) { return false; }

    @Override public byte[] getSaveRam() { return null; }

    @Override public void setSaveRam(byte[] sram) { /* no-op */ }

    @Override public void close() { /* nothing to close */ }

    public String getSystemDir() { return systemDir; }
    public String getSaveDir() { return saveDir; }
}
