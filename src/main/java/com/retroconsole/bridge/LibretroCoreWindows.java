package com.retroconsole.bridge;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/** JNA interface to .libheadless_gl.dll — headless WGL context on Windows. */
interface HeadlessGLWin extends com.sun.jna.Library {
    int hlg_init_ex(int api, int major, int minor, int flags);
    void hlg_destroy();
    Pointer hlg_get_proc_address(String sym);
    void hlg_read_pixels(int[] viewport, Pointer pixels, int maxPixels, int reqW, int reqH);
    void hlg_dump_hw_render(Pointer data, int size);
    Pointer hlg_get_framebuffer_ptr();
    Pointer hlg_get_proc_address_ptr();
    Pointer hlg_get_log_cb_ptr();
    int hlg_make_current();
    void hlg_release();
    void hlg_debug_fbo();
    int hlg_resize(int w, int h);
    String hlg_get_gpu_info();
}

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

    /**
     * Static leak of every {@link LibretroBridge INSTANCE} we have ever
     * opened. JNA unloads the native .dll the moment the last
     * {@code Native.load()} reference becomes unreachable; if the .dll is
     * unloaded while a callback trampoline still dangles on the C side
     * (an asynchronous callback frame mid-call) we get a 0xC0000005
     * "invalid memory access" with
     * {@code faulting module = flycast_libretro.dll_unloaded}. Pinning the
     * proxy here keeps the .dll resident for the lifetime of this class-
     * loader, so the JVM never tries to FreeLibrary the .dll while we
     * might still be calling into it.
     *
     * <p>The list is also how we "swap cores": when a console picks a new
     * core, we drop the previous entry but keep the rest. Memory cost is
     * bounded by how many distinct cores the player tries in one session
     * (typically 1-2).
     */
    private static final java.util.List<LibretroBridge> KEEP_LOADED = new java.util.ArrayList<>();
    /** JNA trampolines registered with native cores — must never be GC'd. */
    private static final java.util.List<Object> PINNED_CALLBACKS = new java.util.ArrayList<>();

    private static final Pointer RETRO_HW_FRAME_BUFFER_VALID = Pointer.createConstant(-1);

    // HW render (Flycast)
    private boolean hwRenderActive = false;
    private boolean hwGpuLoggedOnEmulatorThread = false;
    private boolean headlessGlReady = false;
    private int headlessGlApi = -1;
    private int hwPbufW = 0;
    private int hwPbufH = 0;
    private Pointer hwContextReset = null;
    private Pointer hwContextDestroy = null;
    private boolean hwContextResetDone = false;
    private HeadlessGLWin headlessGl;

    private HeadlessGLWin headlessGl() {
        if (headlessGl == null) {
            String path = Paths.get("config/retroconsole/cores/.libheadless_gl.dll")
                    .toAbsolutePath().toString();
            headlessGl = Native.load(path, HeadlessGLWin.class);
        }
        return headlessGl;
    }

    private boolean supportsHwRender() {
        try {
            headlessGl();
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Headless GL DLL not available: {}", t.getMessage());
            return false;
        }
    }

    private boolean ensureHeadlessGl(int ctxType) {
        int api = (ctxType == 1 || ctxType == 2 || ctxType == 4) ? 1 : 0;
        int major = 3;
        int minor = (api == 1) ? 0 : (ctxType == 3 ? 3 : 1);
        int flags = 0;
        int profileKey = (api & 0xFF) | ((flags & 0xFF) << 8) | ((minor & 0xFF) << 16);
        if (headlessGlReady && headlessGlApi == profileKey) return true;
        if (headlessGlReady) {
            try { headlessGl().hlg_destroy(); } catch (Throwable ignored) {}
            headlessGlReady = false;
            headlessGlApi = -1;
        }
        try {
            int glOk = headlessGl().hlg_init_ex(api, major, minor, flags);
            headlessGlReady = glOk != 0;
            if (headlessGlReady) headlessGlApi = profileKey;
            String apiName = api == 1 ? "GLES" + major : "GL " + major + "." + minor;
            LOGGER.info("Headless GL context ({}): {}", apiName, headlessGlReady ? "OK" : "FAILED");
            if (headlessGlReady) {
                LOGGER.info("Headless GL GPU: {}", headlessGl().hlg_get_gpu_info());
            }
            return headlessGlReady;
        } catch (Throwable t) {
            LOGGER.warn("Headless GL init failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * Tear down HW render after a failed {@code retro_load_game} or before
     * {@code hlg_destroy}. Flycast calls {@code context_reset} during load;
     * destroying WGL without {@code context_destroy} leaves native GL state
     * dangling and often kills the JVM with 0xC0000005.
     */
    private void teardownHwRender() {
        if (!hwRenderActive && !headlessGlReady) return;
        if (hwContextResetDone && hwContextDestroy != null
                && Pointer.nativeValue(hwContextDestroy) != 0) {
            try {
                headlessGl().hlg_make_current();
                LOGGER.info("Calling context_destroy @ {}", hwContextDestroy);
                com.sun.jna.Function.getFunction(hwContextDestroy).invokeVoid(new Object[0]);
                LOGGER.info("context_destroy completed");
            } catch (Throwable t) {
                LOGGER.warn("context_destroy failed: {}", t.getMessage());
            }
        }
        if (headlessGlReady) {
            try {
                headlessGl().hlg_release();
            } catch (Throwable ignored) {}
        }
        hwRenderActive = false;
        hwContextResetDone = false;
        hwContextReset = null;
        hwContextDestroy = null;
        hwGpuLoggedOnEmulatorThread = false;
        hwPbufW = 0;
        hwPbufH = 0;
    }

    /** Set after a successful {@code retro_init()}. */
    private boolean coreInitialized;

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
    /**
     * Diagnostics helpers. We never want Flycast on Windows to silently
     * escape into a SIGSEGV the JVM cannot catch — its JVM exit code is
     * 0xC0000005 and Minecraft never recovers. Tag cores whose .dll name
     * we recognise so future investigations can branch on them.
     */
    private boolean isFlycastCore() {
        return corePath != null
                && corePath.getFileName().toString().toLowerCase().contains("flycast");
    }

    private static void pinForever(Object ref) {
        if (ref != null && !PINNED_CALLBACKS.contains(ref)) {
            PINNED_CALLBACKS.add(ref);
        }
    }

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
            LOGGER.info("Core loaded. API version: {} (corePath={})", apiVersion, corePath.getFileName());

            // Pin every loaded core so JNA never FreeLibrary's a .dll while
            // native code might still hold our callback pointers.
            if (!KEEP_LOADED.contains(this.core)) {
                KEEP_LOADED.add(this.core);
                LOGGER.debug("Pinned libretro core load (held {} libraries so far)",
                        KEEP_LOADED.size());
            }

            if (isFlycastCore()) {
                // FlycastWindowsCrashPatch.apply(corePath);
            }

            LOGGER.info("setupCallbacks()");
            setupCallbacks();

            LOGGER.info("retro_init()");
            core.retro_init();
            coreInitialized = true;
            LOGGER.info("retro_init() returned. Core initialized.");
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

        LibretroBridge.RetroVideoRefresh videoCb = (data, w, h, pitch) -> {
            if (w <= 0 || h <= 0) {
                if (hwRenderActive) newFrame = true;
                return;
            }
            synchronized (frameLock) {
                long dataAddr = data != null ? Pointer.nativeValue(data) : 0;
                boolean hwFb = hwRenderActive && (data == null || dataAddr == -1 || dataAddr == 0);
                if (!hwFb && (data == null || dataAddr == 0)) {
                    if (frameBuffer.length == w * h) newFrame = true;
                    return;
                }
                int len = w * h;
                if (frameBuffer.length != len) frameBuffer = new int[len];
                int[] dst = frameBuffer;

                if (hwFb) {
                    try {
                        int surfW = Math.max(w, hwPbufW);
                        int surfH = Math.max(h, hwPbufH);
                        if (surfW != hwPbufW || surfH != hwPbufH) {
                            headlessGl().hlg_resize(surfW, surfH);
                            hwPbufW = surfW;
                            hwPbufH = surfH;
                        }
                        int[] vp = new int[4];
                        Memory nativePixels = new Memory((long) len * 4L);
                        headlessGl().hlg_read_pixels(vp, nativePixels, len, w, h);
                        for (int i = 0; i < len; i++) {
                            int px = nativePixels.getInt((long) i * 4);
                            dst[i] = 0xFF000000 | (px & 0x00FFFFFF);
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("HW readback failed: {}", t.getMessage());
                    }
                    newFrame = true;
                    return;
                }

                switch (pixelFormat) {
                    case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888: {
                        int stride = (int) (pitch / 4);
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                int srcOffset = y * stride + x;
                                int pixel;
                                if (data == null) {
                                    pixel = 0;
                                } else {
                                    pixel = data.getInt((long) srcOffset * 4);
                                }
                                dst[y * w + x] = 0xFF000000 | (pixel & 0x00FFFFFF);
                            }
                        }
                        break;
                    }
                    case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565: {
                        // 16 bits per pixel, little-endian on x86/amd64.
                        // Layout: RRRRRGGGGGGBBBBB.
                        int stride = (int) (pitch / 2);
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                int srcOffset = y * stride + x;
                                short raw = (data == null) ? 0 : data.getShort((long) srcOffset * 2);
                                int r = ((raw >> 11) & 0x1F) << 3;
                                int g = ((raw >> 5)  & 0x3F) << 2;
                                int b = ( raw        & 0x1F) << 3;
                                dst[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                        }
                        break;
                    }
                    case LibretroBridge.RETRO_PIXEL_FORMAT_0RGB1555: {
                        int stride = (int) (pitch / 2);
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                int srcOffset = y * stride + x;
                                short raw = (data == null) ? 0 : data.getShort((long) srcOffset * 2);
                                int r = ((raw >> 10) & 0x1F) << 3;
                                int g = ((raw >> 5)  & 0x1F) << 3;
                                int b = ( raw        & 0x1F) << 3;
                                dst[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                        }
                        break;
                    }
                    default:
                        java.util.Arrays.fill(dst, 0xFF000000);
                        break;
                }
                newFrame = true;
            }
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

        pinForever(envCallback);
        pinForever(videoCallback);
        pinForever(audioSampleCallback);
        pinForever(audioBatchCallback);
        pinForever(inputPollCallback);
        pinForever(inputStateCallback);
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

    /** Most-recent accepted core options, key → default value. Populated
     *  dynamically by SET_VARIABLES / SET_CORE_OPTIONS / SET_CORE_OPTIONS_V2
     *  so cores never have names hard-coded here. */
    private final java.util.Map<String, String> coreOptions = new java.util.LinkedHashMap<>();
    /** Keep allocated Memory alive so GC does not free option value strings
     *  before the core reads them (e.g. via GET_VARIABLE). */
    private final java.util.List<Memory> allocatedOptionMemory = new java.util.ArrayList<>();
    /** Cores advertise which option version we agreed to support. */
    private int coreOptionsVersion = 2;

    /** Whether SET_AUDIO_BUFFER_STATUS_CALLBACK has been registered (we decline,
     *  but the core still sends the cmd and we ack it). */
    private boolean audioBufferStatusRequested = false;

    /**
     * Full environment callback. Step 8: covers v1 AND v2 core options,
     * pixel-format conversion, audio pacing hints, and the dozen misc
     * commands cores ask during load. Everything is dynamic — we never
     * hard-code an option key; we only store whatever the core declares
     * and hand it back on GET_VARIABLE.
     */
    private boolean handleEnvironment(int cmd, Pointer data) {
        int base = LibretroEnvironment.normalize(cmd);
        switch (base) {
            // ----- memory + bookkeeping -----
            case LibretroEnvironment.GET_OVERSCAN:
                return false;

            case LibretroEnvironment.GET_CAN_DUPE:
                if (data != null) data.setByte(0, (byte) 1);
                return true;

            case LibretroEnvironment.GET_CORE_OPTIONS_VERSION:
                if (data != null) data.setInt(0, coreOptionsVersion);
                LOGGER.debug("Core requested GET_CORE_OPTIONS_VERSION → {}", coreOptionsVersion);
                return true;

            case LibretroEnvironment.GET_MESSAGE_INTERFACE_VERSION:
                if (data != null) data.setInt(0, 1);
                return true;

            case LibretroEnvironment.GET_VARIABLE_UPDATE:
                // 0 = no updates since last query; 1 = yes.
                if (data != null) data.setByte(0, (byte) 0);
                return true;

            case LibretroEnvironment.GET_LANGUAGE:
                if (data != null) data.setInt(0, 0 /* RETRO_LANGUAGE_ENGLISH */);
                return true;

            // ----- pixel format + geometry -----
            case LibretroEnvironment.SET_PIXEL_FORMAT:
                if (data != null) {
                    this.pixelFormat = data.getInt(0);
                    LOGGER.info("Core set pixel format: {}",
                            switch (pixelFormat) {
                                case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888 -> "XRGB8888";
                                case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565   -> "RGB565";
                                case LibretroBridge.RETRO_PIXEL_FORMAT_0RGB1555 -> "0RGB1555";
                                default -> String.valueOf(pixelFormat);
                            });
                }
                return true;

            case LibretroEnvironment.SET_GEOMETRY:
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

            // ----- directory lookups -----
            case LibretroEnvironment.GET_SYSTEM_DIRECTORY:
                return returnCString(data, systemDir, true);

            case LibretroEnvironment.GET_SAVE_DIRECTORY:
                return returnCString(data, saveDir, false);

            case LibretroEnvironment.GET_LIBRETRO_PATH:
                if (data == null || corePath == null) return false;
                return returnCString(data, corePath.toAbsolutePath().toString(), false);

            case LibretroEnvironment.GET_VARIABLE:
                return handleGetVariable(data);

            // ----- v1 core options -----
            case LibretroEnvironment.SET_VARIABLES: {
                LOGGER.info("Core sent SET_VARIABLES (v1)");
                if (data == null) return true;
                parseV1Strings(data);
                return true;
            }

            case LibretroEnvironment.SET_CORE_OPTIONS: {
                LOGGER.info("Core sent SET_CORE_OPTIONS (v1 struct)");
                if (data == null) return true;
                parseV1Structs(data, false /* not v2 */);
                return true;
            }

            // ----- v2 core options -----
            case LibretroEnvironment.SET_CORE_OPTIONS_V2: {
                LOGGER.info("Core sent SET_CORE_OPTIONS_V2");
                if (data == null) return true;
                parseV2Defs(data);
                return true;
            }

            case LibretroEnvironment.SET_CORE_OPTIONS_V2_INTL: {
                LOGGER.info("Core sent SET_CORE_OPTIONS_V2_INTL");
                if (data == null) return true;
                parseV2Intl(data);
                return true;
            }

            case LibretroEnvironment.SET_VARIABLE:
                // libretro 1.15+ individual SET_VARIABLE (key/value pair).
                // data is two pointers: [key, value].
                if (data != null) {
                    Pointer keyPtr = data.getPointer(0);
                    Pointer valPtr = data.getPointer(Native.POINTER_SIZE);
                    String k = readCString(keyPtr);
                    String v = readCString(valPtr);
                    if (k != null && !k.isEmpty()) {
                        coreOptions.put(k, v != null ? v : "");
                        LOGGER.debug("Core SET_VARIABLE: {} = {}", k, v);
                    }
                }
                return true;

            case LibretroEnvironment.SET_CORE_OPTIONS_DISPLAY:
            case LibretroEnvironment.SET_MINIMUM_AUDIO_LATENCY:
            case LibretroEnvironment.SET_AUDIO_CALLBACK:
            case LibretroEnvironment.SET_INPUT_DESCRIPTORS:
            case LibretroEnvironment.SET_MESSAGE:
            case LibretroEnvironment.SET_SYSTEM_AV_INFO:
            case LibretroEnvironment.SET_CONTROLLER_INFO:
            case LibretroEnvironment.SET_SERIALIZATION_QUIRKS:
            case LibretroEnvironment.SET_PERFORMANCE_LEVEL:
            case LibretroEnvironment.SET_SUBSYSTEM_INFO:
                return true;

            case LibretroEnvironment.SET_AUDIO_BUFFER_STATUS_CALLBACK:
                audioBufferStatusRequested = true;
                return true;

            case LibretroEnvironment.SET_SUPPORT_NO_GAME:
                // 0 = the core does not support being run without a game loaded.
                if (data != null) data.setByte(0, (byte) 0);
                return true;

            // ----- input + HW -----
            // Performance interface (CPU feature flags + ticks). Cores
            // (especially Flycast) sometimes sanity-check perf callbacks at
            // load time. Returning true with null callbacks can crash cores
            // that later dereference the perf function pointer; returning
            // false makes them fall back to platform-default timing (on
            // Windows that means QueryPerformanceCounter).
            case LibretroEnvironment.GET_PERF_INTERFACE:
                LOGGER.info("Core requested GET_PERF_INTERFACE — declining (no perf callbacks).");
                return false;

            case 51: // GET_INPUT_BITMASKS (experimental)
                // Tell the core we honour the GET_INPUT_BITMASKS short-circuit
                // (inputStateCallback already handles RETRO_DEVICE_ID_JOYPAD_MASK).
                return true;

            case LibretroEnvironment.SET_HW_RENDER:
                if (data == null) return false;
                try {
                    int ctxType = data.getInt(0);
                    String ctxName = switch (ctxType) {
                        case 0 -> "NONE";
                        case 1 -> "OPENGL";
                        case 2 -> "OPENGLES2";
                        case 3 -> "OPENGL_CORE";
                        case 4 -> "OPENGLES3";
                        case 6 -> "VULKAN";
                        default -> "UNKNOWN(" + ctxType + ")";
                    };
                    LOGGER.info("Core requests HW render: context_type={} ({})", ctxType, ctxName);

                    if (ctxType == 0) {
                        hwRenderActive = false;
                        return true;
                    }
                    if (!supportsHwRender()) {
                        LOGGER.info("Rejecting {} — headless GL DLL missing", ctxName);
                        return false;
                    }
                    if (!ensureHeadlessGl(ctxType)) {
                        LOGGER.info("Rejecting {} — headless WGL init failed", ctxName);
                        return false;
                    }
                    if (ctxType != 1 && ctxType != 3 && ctxType != 4) {
                        LOGGER.info("Rejecting {} — only OpenGL contexts supported", ctxName);
                        return false;
                    }

                    int glMajor = 3;
                    int glMinor = ctxType == 3 ? 3 : 1;
                    headlessGl().hlg_dump_hw_render(data, 80);
                    Pointer fbPtr = headlessGl().hlg_get_framebuffer_ptr();
                    Pointer procPtr = headlessGl().hlg_get_proc_address_ptr();
                    LOGGER.info("  get_framebuffer @ {}, get_proc_address @ {}", fbPtr, procPtr);
                    data.setPointer(16, fbPtr);
                    data.setPointer(24, procPtr);
                    data.setInt(36, glMajor);
                    data.setInt(40, glMinor);
                    data.setByte(44, (byte) 1);

                    hwContextReset = data.getPointer(8);
                    hwContextDestroy = data.getPointer(48);
                    hwContextResetDone = false;

                    if (isFlycastCore()) {
                        Pointer contextResetPtr = data.getPointer(8);
                        if (contextResetPtr != null && Pointer.nativeValue(contextResetPtr) != 0) {
                            LOGGER.info("  Calling context_reset @ {}", contextResetPtr);
                            com.sun.jna.Function.getFunction(contextResetPtr).invokeVoid(new Object[0]);
                            hwContextResetDone = true;
                            LOGGER.info("  context_reset completed");
                        }
                    }

                    hwRenderActive = true;
                    LOGGER.info("HW render context provided for {} (headless WGL)", ctxName);
                    return true;
                } catch (Throwable t) {
                    LOGGER.error("Failed to handle SET_HW_RENDER", t);
                    return false;
                }

            case LibretroEnvironment.GET_PREFERRED_HW_RENDER:
                if (data != null && supportsHwRender()) {
                    data.setInt(0, LibretroEnvironment.RETRO_HW_CONTEXT_OPENGL_CORE);
                    LOGGER.info("Core requested GET_PREFERRED_HW_RENDER -> OPENGL_CORE");
                    return true;
                }
                if (data != null) data.setInt(0, 0);
                return true;

            case LibretroEnvironment.GET_RUMBLE_INTERFACE:
                return false; // no rumble yet

            case LibretroEnvironment.GET_LOG_INTERFACE:
                if (data == null) return false;
                try {
                    Pointer nativeLogCb = headlessGl().hlg_get_log_cb_ptr();
                    if (nativeLogCb != null && Pointer.nativeValue(nativeLogCb) != 0) {
                        data.setPointer(0, nativeLogCb);
                        pinForever(nativeLogCb);
                        LOGGER.info("Providing native log callback for core");
                        return true;
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Native log cb unavailable: {}", t.getMessage());
                }
                return false;

            case 45: // GET_VFS_INTERFACE (experimental)
            case 0x1002e: // some VFS-related cmd (experimented by Genesis)
            case 0x1002f:
                return false;

            case LibretroEnvironment.SET_KEYBOARD_CALLBACK:
                return false;

            // ----- PCSX-ReARMed / PS1 specifics ----------------------------------
            case LibretroEnvironment.GET_DISK_CONTROL_INTERFACE_VERSION:
                if (data != null) data.setInt(0, 1);
                return true;
            case LibretroEnvironment.SET_DISK_CONTROL_INTERFACE:
                return true;
            case LibretroEnvironment.SET_DISK_CONTROL_EXT_INTERFACE:
                return true;
            case 36: // SET_MEMORY_MAPS (experimental)
                return true;
            case 40: // GET_CURRENT_SOFTWARE_FRAMEBUFFER (experimental)
                // Some cores (PCSX-ReARMed, Genesis) ask for a pointer to a
                // software framebuffer so they can write pixels directly via
                // raw memory. Per libretro.h, return false if we cannot
                // provide that path — the core will fall back to the
                // regular video_refresh callback.
                //
                // Returning true with a null pointer is what we did before,
                // and Genesis crashed (invalid memory access) inside
                // retro_run the first time it tried to deref that null.
                // Returning false stops that crash entirely. Other SW-only
                // cores handle this gracefully.
                return false;
            case LibretroEnvironment.SET_FASTFORWARDING_OVERRIDE:
                return true;

            // Core-private (non-standard) cmds that PCSX-ReARMed issues.
            // We don't know what they want, but returning false makes
            // PCSX-ReARMed complain. Accepting silently is harmless here.
            case 54: // 0x36 — PCSX-ReARMed private
            case 69: // 0x45 — PCSX-ReARMed private
            case 0x10030:
                return true;

            default:
                LOGGER.warn("Unhandled env cmd {} (raw 0x{})",
                        LibretroEnvironment.name(cmd), Integer.toHexString(cmd));
                return false;
        }
    }

    // ----- handleEnvironment helpers --------------------------------------

    /** Write a NUL-terminated UTF-8 string at {@code data} for the core to read.
     *  Allocates a fresh Memory each call and parks it in
     *  {@link #allocatedOptionMemory} so the GC doesn't pull it back. */
    private boolean returnCString(Pointer data, String s, boolean useSystemDirSlot) {
        if (data == null || s == null) return false;
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Memory m = new Memory(bytes.length + 1L);
        m.write(0, bytes, 0, bytes.length);
        m.setByte(bytes.length, (byte) 0);
        allocatedOptionMemory.add(m);
        data.setPointer(0, m);
        if (useSystemDirSlot) {
            // System/save dir pointers are stored on dedicated slots so they
            // stay valid as long as the core holds them. Keep the most recent one.
            if (systemDir != null && s.equals(systemDir) && persistentSystemDir == null) {
                persistentSystemDir = m;
            }
        } else if (saveDir != null && s.equals(saveDir) && persistentSaveDir == null) {
            persistentSaveDir = m;
        }
        return true;
    }

    private static String readCString(Pointer p) {
        if (p == null) return null;
        try { return p.getString(0); } catch (Throwable t) { return null; }
    }

    /**
     * v1: SET_VARIABLES hands us an array of {@code retro_variable}
     *     alternating key/value strings, terminated by a NULL key pointer.
     */
    private void parseV1Strings(Pointer array) {
        long offset = 0;
        int count = 0;
        while (true) {
            Pointer keyPtr = array.getPointer(offset);
            if (keyPtr == null) break;
            String key = readCString(keyPtr);
            offset += Native.POINTER_SIZE;
            if (key == null || key.isEmpty()) break;
            Pointer valPtr = array.getPointer(offset);
            offset += Native.POINTER_SIZE;
            String value = readCString(valPtr);
            coreOptions.put(key, parseDefaultFromV1String(value));
            count++;
        }
        LOGGER.info("SET_VARIABLES: stored {} key(s)", count);
    }

    /** v1 string format: "description; option1|option2|default".
     *  The default is the last option after the rightmost "|". */
    private static String parseDefaultFromV1String(String value) {
        if (value == null || value.isEmpty()) return "";
        int semi = value.indexOf(';');
        if (semi < 0) return "";
        String opts = value.substring(semi + 1).trim();
        int pipe = opts.lastIndexOf('|');
        if (pipe < 0) return opts;
        return opts.substring(pipe + 1).trim();
    }

    /**
     * v1 (newer form): SET_CORE_OPTIONS hands us a pointer to a contiguous
     * array of {@code retro_core_option_definition} structs. Termination:
     * a def whose {@code key} is null/empty, or any def whose
     * {@code default_value} is null in v2 mode.
     *
     * <p>Because JNA's Structure() does not expose a clean way to attach
     * to an arbitrary offset inside native memory, we read fields through
     * {@link Pointer#getString(long)} and friends using pre-computed
     * offsets based on the configured field order. The layout is fixed by
     * libretro.h regardless of platform, so this is safe.
     */
    private void parseV1Structs(Pointer array, boolean v2) {
        if (array == null) return;
        // Field offsets within RetroCoreOptionDefinition (libretro.h layout):
        //   0 : String key         (8 bytes on x64)
        //   8 : String desc        (8)
        //  16 : String info        (8)  — v2 only
        //  24 : Pointer values[64] (8 * 64 = 512) — v2 only
        // 536 : String default_value (8)
        // 544 : end of struct
        final int KEY_OFF     = 0;
        final int DEFAULT_OFF = v2 ? 536 : 16;

        long off = 0;
        int count = 0;
        while (true) {
            String key;
            try {
                key = array.getString(KEY_OFF + off);
            } catch (Throwable t) {
                break;
            }
            if (key == null || key.isEmpty()) break;
            String def = null;
            try {
                def = array.getString(DEFAULT_OFF + off);
            } catch (Throwable t) {
                def = "";
            }
            coreOptions.put(key, def != null ? def : "");
            count++;
            off += 544; // sizeof(struct retro_core_option_definition)
            if (count > 256) break;
        }
        LOGGER.info("SET_CORE_OPTIONS: stored {} key(s)", count);
    }

    /** v2: SET_CORE_OPTIONS_V2 hands us a single Pointer to a definitions array,
     *  same struct shape as the v1 newer form, addressed via data.getPointer(). */
    private void parseV2Defs(Pointer data) {
        Pointer array = data.getPointer(0);
        if (array == null) return;
        parseV1Structs(array, true);
    }

    /**
     * v2-intl: SET_CORE_OPTIONS_V2_INTL hands us a {@code retro_core_options_v2_intl}
     * struct. Walking the embedded structure with JNA on the read-path is
     * fiddly (JNA Structure semantics do not map cleanly onto a pointer
     * that the core itself constructs), so for now we accept the cmd and
     * log it. The matching keys reach us via the parallel
     * {@code SET_CORE_OPTIONS_V2} call the core is required to make, so we
     * do not lose coverage.
     */
    private void parseV2Intl(Pointer data) {
        LOGGER.debug("SET_CORE_OPTIONS_V2_INTL: accepted (parallel V2 defs already parsed).");
    }

    private boolean handleGetVariable(Pointer data) {
        try {
            if (data == null) return false;
            long dataAddr = Pointer.nativeValue(data);
            if (dataAddr == 0) return false;
            Pointer keyPtr = data.getPointer(0);
            if (keyPtr == null) return false;
            long keyAddr = Pointer.nativeValue(keyPtr);
            if (keyAddr == 0 || keyAddr < 0x10000L) return false;
            String key = readCString(keyPtr);
            if (key == null || key.isEmpty()) return false;

            // Flycast: return false → core uses compiled-in defaults. Returning
            // true without SET_CORE_OPTIONS defs triggers DEBUGBREAK in the core.
            if (isFlycastCore()) {
                return false;
            }

            String val = coreOptions.get(key);
            if (val == null) return false;
            Memory m = new Memory(val.length() + 1L);
            m.setString(0, val);
            allocatedOptionMemory.add(m);
            data.setPointer(Native.POINTER_SIZE, m);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("GET_VARIABLE failed: {}", t.getMessage());
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
        LOGGER.info("loadGame({}) [{}]", romPath, isFlycastCore() ? "FLYCAST" : "other");

        LibretroBridge.RetroGameInfo info;
        try {
            info = buildGameInfo(romPath);
            LOGGER.info("buildGameInfo: ok ({})", info.path);
        } catch (Exception e) {
            LOGGER.error("Failed to build game info for {}: {}", romPath, e.getMessage(), e);
            return false;
        }

        boolean ok;
        try {
            if (isFlycastCore() && supportsHwRender()) {
                ensureHeadlessGl(LibretroEnvironment.RETRO_HW_CONTEXT_OPENGL_CORE);
            }
            LOGGER.info("about to call core.retro_load_game()...");
            ok = core.retro_load_game(info);
            LOGGER.info("retro_load_game returned: {}", ok);
        } catch (Throwable t) {
            LOGGER.error("retro_load_game CRASHED for {}: {}", romPath, t.getMessage(), t);
            teardownHwRender();
            freeGameInfo(info);
            return false;
        }

        if (!ok) {
            LOGGER.error("Core rejected ROM: {}", romPath.getFileName());
            teardownHwRender();
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

        if (hwRenderActive) {
            try {
                headlessGl().hlg_release();
                LOGGER.info("WGL context released from init thread");
            } catch (Throwable t) {
                LOGGER.warn("Failed to release WGL context: {}", t.getMessage());
            }
        }

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
        String abs = romPath.toAbsolutePath().normalize().toString();
        info.path = isFlycastCore() ? abs.replace('\\', '/') : abs;
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
            allocatedOptionMemory.add(mem);
        }
        info.write();
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
        if (core != null && gameLoaded) {
            if (hwRenderActive) {
                try {
                    if (headlessGl().hlg_make_current() != 0 && !hwGpuLoggedOnEmulatorThread) {
                        hwGpuLoggedOnEmulatorThread = true;
                        LOGGER.info("Emulator thread GPU: {}", headlessGl().hlg_get_gpu_info());
                    }
                } catch (Throwable ignored) {}
            }
            try {
                core.retro_run();
            } catch (Throwable t) {
                LOGGER.warn("retro_run threw: {}", t.getMessage(), t);
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
        if (core == null) {
            LOGGER.debug("close(): core already null — no-op");
            return;
        }
        LOGGER.info("close(): shutting down libretro core {}", corePath);

        teardownHwRender();

        if (gameLoaded) {
            try {
                core.retro_unload_game();
                LOGGER.info("retro_unload_game: ok");
            } catch (Throwable t) {
                LOGGER.warn("retro_unload_game failed: {}", t.getMessage());
            }
            try {
                core.retro_deinit();
                LOGGER.info("retro_deinit: ok");
            } catch (Throwable t) {
                LOGGER.warn("retro_deinit failed: {}", t.getMessage());
            }
        } else if (coreInitialized) {
            try {
                core.retro_deinit();
                LOGGER.info("retro_deinit: ok (game never loaded)");
            } catch (Throwable t) {
                LOGGER.warn("retro_deinit failed: {}", t.getMessage());
            }
        }

        if (headlessGlReady) {
            try { headlessGl().hlg_destroy(); } catch (Throwable ignored) {}
            headlessGlReady = false;
        }

        // Drop our handle but never unpin KEEP_LOADED / PINNED_CALLBACKS.
        this.core = null;
        this.envCallback = null;
        this.videoCallback = null;
        this.audioSampleCallback = null;
        this.audioBatchCallback = null;
        this.inputPollCallback = null;
        this.inputStateCallback = null;
        this.persistentSystemDir = null;
        this.persistentSaveDir = null;
        this.loadedGameInfo = null;
        this.allocatedOptionMemory.clear();
        this.coreInitialized = false;
        synchronized (frameLock) {
            this.frameBuffer = new int[0];
            this.newFrame = false;
        }
        this.gameLoaded = false;

        LOGGER.info("close(): clean shutdown complete");
    }

    public String getSystemDir() { return systemDir; }
    public String getSaveDir() { return saveDir; }
}
