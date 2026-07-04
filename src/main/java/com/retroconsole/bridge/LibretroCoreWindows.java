package com.retroconsole.bridge;

import com.sun.jna.Memory;
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

    /** Register every callback the libretro frontend surface needs.
     *  Environment (Step 2) plus video / audio / input (Steps 4+5). */
    private void setupCallbacks() {
        LibretroBridge.RetroEnvironment env = (cmd, data) -> handleEnvironment(cmd, data);
        this.envCallback = env;
        core.retro_set_environment(env);

        // ----- Video (Step 4) -----
        LibretroBridge.RetroVideoRefresh videoCb = (data, w, h, pitch) -> {
            if (w <= 0 || h <= 0) return; // core signals geometry change
            int len = w * h;
            int[] dst;
            synchronized (frameLock) {
                if (frameBuffer.length != len) frameBuffer = new int[len];
                dst = frameBuffer;
            }
            switch (pixelFormat) {
                case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888: {
                    int stride = (int) (pitch / 4);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int srcOffset = y * stride + x;
                            int pixel;
                            if (data == null) {
                                pixel = 0; // null data on software path means "no frame" — leave black
                            } else {
                                pixel = data.getInt((long) srcOffset * 4);
                            }
                            dst[y * w + x] = 0xFF000000 | (pixel & 0x00FFFFFF);
                        }
                    }
                    break;
                }
                default:
                    // RGB565 / 0RGB1555 / unknown — leave frame black for now.
                    java.util.Arrays.fill(dst, 0xFF000000);
                    break;
            }
            newFrame = true;
        };
        this.videoCallback = videoCb;
        core.retro_set_video_refresh(videoCb);

        // ----- Audio (placeholder — Step 7 will produce sound) -----
        this.audioSampleCallback = (left, right) -> { /* discard */ };
        core.retro_set_audio_sample(audioSampleCallback);
        this.audioBatchCallback = (data, frames) -> frames;
        core.retro_set_audio_sample_batch(audioBatchCallback);

        // ----- Input (Step 6: callbacks wired but step is to actually fill them) -----
        this.inputPollCallback = () -> { /* nothing to poll, state is already set */ };
        core.retro_set_input_poll(inputPollCallback);

        LibretroBridge.RetroInputState inputStateCb = (port, device, index, id) -> {
            if (port != 0) return 0;
            if (device == LibretroBridge.RETRO_DEVICE_JOYPAD) {
                if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_MASK) {
                    int mask = 0;
                    for (int i = 0; i < 16; i++) {
                        if (joypadState.get(i) != 0) mask |= (1 << i);
                    }
                    return (short) mask;
                }
                if (index == 0 && id >= 0 && id < 16) {
                    return (short) joypadState.get(id);
                }
                return 0;
            }
            if (device == LibretroBridge.RETRO_DEVICE_ANALOG) {
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_LEFT) {
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_X) return (short) analogState.get(0);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_Y) return (short) analogState.get(1);
                }
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_X) return (short) analogState.get(2);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_Y) return (short) analogState.get(3);
                }
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
                    if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L ||
                            id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2) return (short) triggerState.get(0);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R ||
                            id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2) return (short) triggerState.get(1);
                }
            }
            return 0;
        };
        this.inputStateCallback = inputStateCb;
        core.retro_set_input_state(inputStateCb);
    }

    /** Strong reference kept so JNA doesn't GC the callback trampoline. */
    private LibretroBridge.RetroEnvironment envCallback;

    /** Native memory holding the system dir string for the core. */
    private Memory persistentSystemDir;
    /** Native memory holding the save dir string for the core. */
    private Memory persistentSaveDir;
    /** Pixel format the core will hand us (set via SET_PIXEL_FORMAT). */
    private int pixelFormat = LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888;

    // ----- Step 4+5: frame buffer + input state shared with callbacks -----

    /** Strong refs to every callback so JNA doesn't GC the trampolines. */
    private LibretroBridge.RetroVideoRefresh videoCallback;
    private LibretroBridge.RetroAudioSample audioSampleCallback;
    private LibretroBridge.RetroAudioSampleBatch audioBatchCallback;
    private LibretroBridge.RetroInputPoll inputPollCallback;
    private LibretroBridge.RetroInputState inputStateCallback;

    /** Most recent frame the core handed us (XRGB8888, ARGB packed). */
    private volatile int[] frameBuffer = new int[0];
    private final Object frameLock = new Object();
    private volatile boolean newFrame = false;

    /** Button state — written from server thread, read by input state callback. */
    private final java.util.concurrent.atomic.AtomicIntegerArray joypadState =
            new java.util.concurrent.atomic.AtomicIntegerArray(16);
    /** Analog sticks: LX, LY, RX, RY. */
    private final java.util.concurrent.atomic.AtomicIntegerArray analogState =
            new java.util.concurrent.atomic.AtomicIntegerArray(4);
    /** L2 / R2 analog triggers. */
    private final java.util.concurrent.atomic.AtomicIntegerArray triggerState =
            new java.util.concurrent.atomic.AtomicIntegerArray(2);

    /**
     * Expanded environment callback. Step 3a: answer every well-known
     * libretro command with a safe default so cores like Nestopia,
     * Genesis, SNES can finish loading. Unknown commands log a WARN and
     * return false (the core may complain in its own log but does not
     * crash).
     */
    private boolean handleEnvironment(int cmd, Pointer data) {
        switch (cmd) {
            case LibretroEnvironment.GET_CAN_DUPE: {
                if (data != null) data.setByte(0, (byte) 1);
                return true;
            }

            case LibretroEnvironment.SET_PIXEL_FORMAT: {
                if (data != null) {
                    this.pixelFormat = data.getInt(0);
                    LOGGER.info("Core set pixel format: {}",
                            pixelFormat == LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888
                                    ? "XRGB8888" : String.valueOf(pixelFormat));
                }
                return true;
            }

            case LibretroEnvironment.SET_GEOMETRY: {
                if (data != null) {
                    int w = data.getInt(0);
                    int h = data.getInt(4);
                    if (w > 0 && h > 0) {
                        this.width = w;
                        this.height = h;
                        LOGGER.info("Core set geometry: {}x{}", w, h);
                    }
                }
                return true;
            }

            case LibretroEnvironment.GET_SYSTEM_DIRECTORY: {
                if (data != null && systemDir != null) {
                    if (persistentSystemDir == null || persistentSystemDir.size() < systemDir.length() + 1) {
                        persistentSystemDir = new Memory(systemDir.length() + 1L);
                        persistentSystemDir.setString(0, systemDir);
                    }
                    data.setPointer(0, persistentSystemDir);
                    return true;
                }
                return false;
            }

            case LibretroEnvironment.GET_SAVE_DIRECTORY: {
                if (data != null && saveDir != null) {
                    if (persistentSaveDir == null || persistentSaveDir.size() < saveDir.length() + 1) {
                        persistentSaveDir = new Memory(saveDir.length() + 1L);
                        persistentSaveDir.setString(0, saveDir);
                    }
                    data.setPointer(0, persistentSaveDir);
                    return true;
                }
                return false;
            }

            case LibretroEnvironment.GET_LIBRETRO_PATH: {
                // Point the core back at the .dll we loaded so it can self-identify.
                if (data != null && corePath != null) {
                    String p = corePath.toAbsolutePath().toString();
                    Memory m = new Memory(p.length() + 1L);
                    m.setString(0, p);
                    data.setPointer(0, m);
                    return true;
                }
                return false;
            }

            case LibretroEnvironment.SET_AUDIO_CALLBACK: {
                // We currently do not produce audio (Step 7 territory) but
                // accept the callback registration so cores that expect it
                // do not refuse to load.
                return true;
            }

            case LibretroEnvironment.SET_INPUT_DESCRIPTORS:
            case LibretroEnvironment.SET_MESSAGE:
            case LibretroEnvironment.SET_SYSTEM_AV_INFO:
            case LibretroEnvironment.SET_CONTROLLER_INFO:
            case LibretroEnvironment.SET_SERIALIZATION_QUIRKS:
            case LibretroEnvironment.SET_PERFORMANCE_LEVEL:
            case LibretroEnvironment.SET_DISK_CONTROL_INTERFACE:
            case LibretroEnvironment.SET_VARIABLES:
            case LibretroEnvironment.SET_CORE_OPTIONS:
                return true;

            case LibretroEnvironment.SET_HW_RENDER: {
                // Step 5+ might re-enable HW for cores that really need it;
                // for now refuse every HW context so the core falls back
                // to software pixel format which we already support.
                LOGGER.info("Core requested SET_HW_RENDER — refusing (Windows HW renderer not implemented).");
                return false;
            }

            case LibretroEnvironment.GET_PREFERRED_HW_RENDER: {
                // Tell the core we have no preference; it will then NOT
                // ask for HW_RENDER again because it has been refused.
                if (data != null) data.setInt(0, 0 /* RETRO_HW_CONTEXT_NONE */);
                return true;
            }

            case LibretroEnvironment.GET_LOG_INTERFACE: {
                // We could wire a JNA logger here, but for now decline —
                // cores stay silent on Windows, which is fine.
                return false;
            }

            default:
                LOGGER.warn("Unhandled env cmd {} (raw 0x{})",
                        LibretroEnvironment.name(cmd), Integer.toHexString(cmd));
                return false;
        }
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

    @Override
    public void runFrame() {
        // Step 5: actually drive the core forward. No HW context switch —
        // Windows impl refuses SET_HW_RENDER so the core stays on the
        // software pixel format path.
        if (core != null && gameLoaded) {
            try {
                core.retro_run();
            } catch (Throwable t) {
                LOGGER.warn("retro_run threw: {}", t.getMessage());
            }
        }
    }

    @Override
    public boolean pollFrame(int[] dst) {
        if (dst == null || dst.length == 0) return false;
        if (!newFrame) return false;
        synchronized (frameLock) {
            if (frameBuffer.length == 0) return false;
            int copyLen = Math.min(frameBuffer.length, dst.length);
            System.arraycopy(frameBuffer, 0, dst, 0, copyLen);
            newFrame = false;
            return true;
        }
    }

    @Override
    public void setButton(int buttonId, boolean pressed) {
        if (buttonId >= 0 && buttonId < 16) {
            joypadState.set(buttonId, pressed ? 1 : 0);
        }
        // Dreamcast L/R → also drive L2/R2 + analog triggers (used by some cores).
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L) {
            joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2, pressed ? 1 : 0);
            triggerState.set(0, pressed ? 32767 : 0);
        }
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R) {
            joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2, pressed ? 1 : 0);
            triggerState.set(1, pressed ? 32767 : 0);
        }
    }

    @Override
    public void setAnalog(int stick, int axis, short value) {
        int idx = stick * 2 + axis;
        if (idx >= 0 && idx < 4) analogState.set(idx, value);
    }

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
