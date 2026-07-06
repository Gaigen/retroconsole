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

    /** Capture HotSpot UEF before Flycast core loads (diagnostic, optional). */
    void hlg_capture_jvm_filter();
    /** Hook RtlAddVectoredExceptionHandler so Flycast VEH goes to tail. */
    void hlg_hook_addveh();
    /** Reset isolated VEH state between core sessions (dispatcher + fly[]). */
    void hlg_reset_veh_session();
}

/**
 * Windows implementation of {@link LibretroCore}.
 *
 * <p>ПОТОКИ: HW-render libretro-ядра требуют, чтобы ВСЯ работа с ядром и
 * GL-контекстом шла на ОДНОМ потоке (retro_init, retro_load_game, context_reset,
 * retro_run, retro_unload_game, retro_deinit, context_destroy). Поэтому весь
 * жизненный цикл ядра гоняется через один {@code retro-core-thread}.
 */
public class LibretroCoreWindows extends LibretroCore {

    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroCoreWindows");

    private static final java.util.List<LibretroBridge> KEEP_LOADED = new java.util.ArrayList<>();
    private static final java.util.List<Object> PINNED_CALLBACKS = new java.util.ArrayList<>();

    /** Точечные переопределения опций для Flycast (имена ключей — как в этой сборке ядра). */
    private static final java.util.Map<String, String> FLYCAST_OVERRIDES = java.util.Map.of(
            "reicast_threaded_rendering", "disabled" // рендер ТОЛЬКО на нашем потоке
    );

    /** Диагностика: печатаем каждый уникальный запрошенный ключ опции один раз. */
    private final java.util.Set<String> loggedFlycastKeys =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ======================================================================
    // ===== ЕДИНСТВЕННЫЙ ПОТОК, ВЛАДЕЮЩИЙ ЯДРОМ И GL-КОНТЕКСТОМ =============
    // ======================================================================

    private final java.util.concurrent.ExecutorService coreThread =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "retro-core-thread");
                t.setDaemon(true);
                return t;
            });

    /** Выполнить задачу на потоке-владельце ядра и дождаться результата. */
    private <T> T onCoreThread(java.util.concurrent.Callable<T> task) {
        if ("retro-core-thread".equals(Thread.currentThread().getName())) {
            try { return task.call(); }
            catch (RuntimeException re) { throw re; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        try {
            return coreThread.submit(task).get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            throw (c instanceof RuntimeException re) ? re : new RuntimeException(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ======================================================================
    // ===== ПУБЛИЧНЫЕ МЕТОДЫ — делегируют на coreThread ====================
    // ======================================================================

    void loadNative() {
        onCoreThread(() -> { loadNativeImpl(); return null; });
    }

    @Override
    public boolean loadGame(Path romPath) {
        return onCoreThread(() -> loadGameImpl(romPath));
    }

    @Override
    public void runFrame() {
        onCoreThread(() -> { runFrameImpl(); return null; });
    }

    @Override
    public void close() throws Exception {
        try {
            onCoreThread(() -> { closeImpl(); return null; });
        } finally {
            coreThread.shutdown();
        }
    }

    // setButton / setAnalog / pollFrame НЕ трогают GL — их можно звать с
    // игрового потока Minecraft (потокобезопасны через атомики + frameLock).

    // ======================================================================
    // ===== HW render (Flycast) state ======================================
    // ======================================================================

    private boolean hwRenderActive = false;
    private boolean hwGpuLoggedOnEmulatorThread = false;
    private boolean headlessGlReady = false;
    private int headlessGlApi = -1;
    private int hwPbufW = 0;
    private int hwPbufH = 0;
    private volatile boolean hwFramePending = false;
    private int hwFrameW = 0;
    private int hwFrameH = 0;
    private Memory hwReadbackBuf = null;
    private int hwReadbackCap = 0;
    private Pointer hwContextReset = null;
    private Pointer hwContextDestroy = null;
    private boolean hwContextResetDone = false;
    private int hwGlMajor = 3;
    private int hwGlMinor = 3;

    private HeadlessGLWin headlessGl;

    private HeadlessGLWin headlessGl() {
        if (headlessGl == null) {
            String path = Paths.get("config/retroconsole/cores/.libheadless_gl.dll")
                    .toAbsolutePath().toString();
            headlessGl = Native.load(path, HeadlessGLWin.class);
        }
        return headlessGl;
    }

    private void captureJvmExceptionFilter() {
        try {
            headlessGl().hlg_capture_jvm_filter();
            LOGGER.info("Captured JVM unhandled-exception filter (pre-Flycast)");
        } catch (Throwable t) {
            LOGGER.warn("hlg_capture_jvm_filter failed: {}", t.getMessage());
        }
    }

    private void hookAddVeh() {
        try {
            headlessGl().hlg_hook_addveh();
            LOGGER.info("Hooked RtlAddVectoredExceptionHandler (Flycast VEH -> tail)");
        } catch (Throwable t) {
            LOGGER.warn("hlg_hook_addveh failed: {}", t.getMessage());
        }
    }

    private void resetVehSession() {
        try {
            headlessGl().hlg_reset_veh_session();
            LOGGER.info("Reset Flycast VEH dispatcher session state");
        } catch (Throwable t) {
            LOGGER.warn("hlg_reset_veh_session failed: {}", t.getMessage());
        }
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
            if (headlessGlReady) {
                headlessGlApi = profileKey;
                hwGlMajor = major;
                hwGlMinor = minor;
            }
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

    private void teardownHwRender() {
        if (!hwRenderActive && !headlessGlReady) return;
        if (headlessGlReady) {
            try { headlessGl().hlg_release(); } catch (Throwable ignored) {}
        }
        hwRenderActive = false;
        hwContextResetDone = false;
        hwContextReset = null;
        hwContextDestroy = null;
        hwGpuLoggedOnEmulatorThread = false;
        hwPbufW = 0;
        hwPbufH = 0;
        hwFramePending = false;
        hwFrameW = 0;
        hwFrameH = 0;
        hwReadbackBuf = null;
        hwReadbackCap = 0;
    }

    /** context_destroy while core is still initialized — must run before retro_deinit. */
    private void destroyHwGlContext() {
        if (!hwContextResetDone || hwContextDestroy == null
                || Pointer.nativeValue(hwContextDestroy) == 0) {
            return;
        }
        try {
            headlessGl().hlg_make_current();
            LOGGER.info("Calling context_destroy @ {}", hwContextDestroy);
            com.sun.jna.Function.getFunction(hwContextDestroy).invokeVoid(new Object[0]);
            LOGGER.info("context_destroy completed");
        } catch (Throwable t) {
            LOGGER.warn("context_destroy failed: {}", t.getMessage());
        } finally {
            hwContextResetDone = false;
            hwContextDestroy = null;
        }
    }

    // ======================================================================
    // ===== Общее состояние ================================================
    // ======================================================================

    private boolean coreInitialized;
    private LibretroBridge core;

    private final String systemDir;
    private final String saveDir;

    public LibretroCoreWindows(Path corePath, String systemDir, String saveDir) {
        super(corePath);
        this.systemDir = systemDir;
        this.saveDir = saveDir;
        LOGGER.info("Windows libretro core stub created for {} (system={}, save={})",
                corePath, systemDir, saveDir);
    }

    private boolean isFlycastCore() {
        return corePath != null
                && corePath.getFileName().toString().toLowerCase().contains("flycast");
    }

    private static void pinForever(Object ref) {
        if (ref != null && !PINNED_CALLBACKS.contains(ref)) {
            PINNED_CALLBACKS.add(ref);
        }
    }

    // ======================================================================
    // ===== loadNativeImpl (на retro-core-thread) ==========================
    // ======================================================================

    private void loadNativeImpl() {
        if (core != null) return;
        if (corePath == null) {
            LOGGER.error("Cannot load Windows libretro core: corePath is null");
            return;
        }
        String absPath = corePath.toAbsolutePath().toString();
        LOGGER.info("Native.load({})", absPath);
        try {
            if (isFlycastCore() && supportsHwRender()) {
                captureJvmExceptionFilter();
                hookAddVeh();
            }
            this.core = Native.load(absPath, LibretroBridge.class);
            int apiVersion = core.retro_api_version();
            LOGGER.info("Core loaded. API version: {} (corePath={})", apiVersion, corePath.getFileName());

            if (!KEEP_LOADED.contains(this.core)) {
                KEEP_LOADED.add(this.core);
                LOGGER.debug("Pinned libretro core load (held {} libraries so far)", KEEP_LOADED.size());
            }

            LOGGER.info("setupCallbacks()");
            setupCallbacks();
            LOGGER.info("retro_init()");
            core.retro_init();
            coreInitialized = true;
            LOGGER.info("retro_init() returned. Core initialized.");
        } catch (Throwable t) {
            LOGGER.error("Failed to load libretro core at {}: {}", absPath, t.getMessage(), t);
            if (this.core != null) {
                try { this.core.retro_deinit(); } catch (Throwable ignored) {}
                KEEP_LOADED.remove(this.core);
            }
            if (isFlycastCore() && supportsHwRender()) {
                resetVehSession();
            }
            this.core = null;
            this.coreInitialized = false;
        }
    }

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
                boolean hwFb = hwRenderActive && (data == null || dataAddr == 1 || dataAddr == -1 || dataAddr == 0);
                if (!hwFb && (data == null || dataAddr == 0)) {
                    if (frameBuffer.length == w * h) newFrame = true;
                    return;
                }
                int len = w * h;
                if (frameBuffer.length != len) frameBuffer = new int[len];
                int[] dst = frameBuffer;

                if (hwFb) {
                    hwFrameW = w;
                    hwFrameH = h;
                    hwFramePending = true;
                    return;
                }

                switch (pixelFormat) {
                    case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888: {
                        int stride = (int) (pitch / 4);
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                int srcOffset = y * stride + x;
                                int pixel = (data == null) ? 0 : data.getInt((long) srcOffset * 4);
                                dst[y * w + x] = 0xFF000000 | (pixel & 0x00FFFFFF);
                            }
                        }
                        break;
                    }
                    case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565: {
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

        this.audioSampleCallback = (left, right) -> { /* discard */ };
        core.retro_set_audio_sample(audioSampleCallback);
        this.audioBatchCallback = (data, frames) -> frames;
        core.retro_set_audio_sample_batch(audioBatchCallback);

        this.inputPollCallback = () -> { /* nothing to poll */ };
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

    private LibretroBridge.RetroEnvironment envCallback;
    private Memory persistentSystemDir;
    private Memory persistentSaveDir;
    private int pixelFormat = LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888;

    private LibretroBridge.RetroVideoRefresh videoCallback;
    private LibretroBridge.RetroAudioSample audioSampleCallback;
    private LibretroBridge.RetroAudioSampleBatch audioBatchCallback;
    private LibretroBridge.RetroInputPoll inputPollCallback;
    private LibretroBridge.RetroInputState inputStateCallback;

    private volatile int[] frameBuffer = new int[0];
    private final Object frameLock = new Object();
    private volatile boolean newFrame = false;

    private final java.util.concurrent.atomic.AtomicIntegerArray joypadState =
            new java.util.concurrent.atomic.AtomicIntegerArray(16);
    private final java.util.concurrent.atomic.AtomicIntegerArray analogState =
            new java.util.concurrent.atomic.AtomicIntegerArray(4);
    private final java.util.concurrent.atomic.AtomicIntegerArray triggerState =
            new java.util.concurrent.atomic.AtomicIntegerArray(2);

    private final java.util.Map<String, String> coreOptions = new java.util.LinkedHashMap<>();
    private final java.util.List<Memory> allocatedOptionMemory = new java.util.ArrayList<>();
    private int coreOptionsVersion = 2;
    private boolean audioBufferStatusRequested = false;

    // ======================================================================
    // ===== environment callback ===========================================
    // ======================================================================

    private boolean handleEnvironment(int cmd, Pointer data) {
        // Гасим по СЫРОМУ коду (switch идёт по нормализованному base, туда эти не попадают):
        if (cmd == 0x10031) { // GET_FASTFORWARDING — шлётся каждый кадр
            if (data != null) data.setByte(0, (byte) 0);
            return true;
        }
        if (cmd == 0x800004) { // приватная cmd этой сборки Flycast
            return true;
        }

        int base = LibretroEnvironment.normalize(cmd);
        switch (base) {
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
                if (data != null) data.setByte(0, (byte) 0);
                return true;
            case LibretroEnvironment.GET_LANGUAGE:
                if (data != null) data.setInt(0, 0 /* RETRO_LANGUAGE_ENGLISH */);
                return true;

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

            case LibretroEnvironment.GET_SYSTEM_DIRECTORY:
                return returnCString(data, systemDir, true);
            case LibretroEnvironment.GET_SAVE_DIRECTORY:
                return returnCString(data, saveDir, false);
            case LibretroEnvironment.GET_LIBRETRO_PATH:
                if (data == null || corePath == null) return false;
                return returnCString(data, corePath.toAbsolutePath().toString(), false);
            case LibretroEnvironment.GET_VARIABLE:
                return handleGetVariable(data);

            case LibretroEnvironment.SET_VARIABLES: {
                LOGGER.info("Core sent SET_VARIABLES (v1)");
                if (data == null) return true;
                parseV1Strings(data);
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS: {
                LOGGER.info("Core sent SET_CORE_OPTIONS (v1 struct)");
                if (data == null) return true;
                parseV1Structs(data, false);
                return true;
            }
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
                if (data != null) data.setByte(0, (byte) 0);
                return true;

            case LibretroEnvironment.GET_PERF_INTERFACE:
                LOGGER.info("Core requested GET_PERF_INTERFACE — declining (no perf callbacks).");
                return false;
            case 51: // GET_INPUT_BITMASKS
                return true;

            case LibretroEnvironment.SET_HW_RENDER:
                return handleSetHwRender(data);

            case LibretroEnvironment.GET_PREFERRED_HW_RENDER:
                if (data != null && supportsHwRender()) {
                    data.setInt(0, LibretroEnvironment.RETRO_HW_CONTEXT_OPENGL_CORE);
                    LOGGER.info("Core requested GET_PREFERRED_HW_RENDER -> OPENGL_CORE");
                    return true;
                }
                if (data != null) data.setInt(0, 0);
                return true;
            case LibretroEnvironment.GET_RUMBLE_INTERFACE:
                return false;
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

            case 45:
            case 0x1002e:
            case 0x1002f:
                return false;
            case LibretroEnvironment.SET_KEYBOARD_CALLBACK:
                return false;

            case LibretroEnvironment.GET_DISK_CONTROL_INTERFACE_VERSION:
                if (data != null) data.setInt(0, 1);
                return true;
            case LibretroEnvironment.SET_DISK_CONTROL_INTERFACE:
                return true;
            case LibretroEnvironment.SET_DISK_CONTROL_EXT_INTERFACE:
                return true;
            case 36:
                return true;
            case 40:
                return false;
            case LibretroEnvironment.SET_FASTFORWARDING_OVERRIDE:
                return true;

            case 54:
            case 69:
            case 0x10030:
                return true;

            default:
                LOGGER.warn("Unhandled env cmd {} (raw 0x{})",
                        LibretroEnvironment.name(cmd), Integer.toHexString(cmd));
                return false;
        }
    }

    /**
     * SET_HW_RENDER: только запоминаем указатели и заполняем структуру.
     * context_reset вызывается в loadGameImpl после успешного retro_load_game
     * (на этом же retro-core-thread).
     */
    private boolean handleSetHwRender(Pointer data) {
        if (data == null) return false;
        try {
            int ctxType = data.getInt(0x00);
            String ctxName = switch (ctxType) {
                case 0 -> "NONE"; case 1 -> "OPENGL"; case 2 -> "OPENGLES2";
                case 3 -> "OPENGL_CORE"; case 4 -> "OPENGLES3"; case 6 -> "VULKAN";
                default -> "UNKNOWN(" + ctxType + ")";
            };
            LOGGER.info("Core requests HW render: context_type={} ({})", ctxType, ctxName);

            if (ctxType == 0) {
                hwRenderActive = false;
                return true;
            }
            if (!supportsHwRender())        { return false; }
            if (!ensureHeadlessGl(ctxType)) { return false; }
            if (ctxType != 1 && ctxType != 3 && ctxType != 4) { return false; }

            hwContextReset   = data.getPointer(0x08);
            hwContextDestroy = data.getPointer(0x30);

            if (headlessGl().hlg_make_current() == 0) {
                LOGGER.error("Failed to make WGL context current during SET_HW_RENDER");
                return false;
            }

            headlessGl().hlg_dump_hw_render(data, 80);

            Pointer fbPtr   = headlessGl().hlg_get_framebuffer_ptr();
            Pointer procPtr = headlessGl().hlg_get_proc_address_ptr();
            pinForever(fbPtr);
            pinForever(procPtr);

            data.setPointer(0x10, fbPtr);
            data.setPointer(0x18, procPtr);
            data.setByte(0x22, (byte) 1);
            data.setInt(0x24, hwGlMajor);
            data.setInt(0x28, hwGlMinor);

            hwContextResetDone = false;
            hwRenderActive = true;
            LOGGER.info("SET_HW_RENDER accepted; context_reset deferred until after retro_load_game");
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to handle SET_HW_RENDER", t);
            return false;
        }
    }

    private boolean returnCString(Pointer data, String s, boolean useSystemDirSlot) {
        if (data == null || s == null) return false;
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Memory m = new Memory(bytes.length + 1L);
        m.write(0, bytes, 0, bytes.length);
        m.setByte(bytes.length, (byte) 0);
        allocatedOptionMemory.add(m);
        data.setPointer(0, m);
        if (useSystemDirSlot) {
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

    private static String parseDefaultFromV1String(String value) {
        if (value == null || value.isEmpty()) return "";
        int semi = value.indexOf(';');
        if (semi < 0) return "";
        String opts = value.substring(semi + 1).trim();
        int pipe = opts.lastIndexOf('|');
        if (pipe < 0) return opts;
        return opts.substring(pipe + 1).trim();
    }

    private void parseV1Structs(Pointer array, boolean v2) {
        if (array == null) return;
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
            String def;
            try {
                def = array.getString(DEFAULT_OFF + off);
            } catch (Throwable t) {
                def = "";
            }
            coreOptions.put(key, def != null ? def : "");
            count++;
            off += 544;
            if (count > 256) break;
        }
        LOGGER.info("SET_CORE_OPTIONS: stored {} key(s)", count);
    }

    private void parseV2Defs(Pointer data) {
        Pointer array = data.getPointer(0);
        if (array == null) return;
        parseV1Structs(array, true);
    }

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

            if (isFlycastCore()) {
                String override = FLYCAST_OVERRIDES.get(key);
                if (override == null) {
                    // ДИАГНОСТИКА: печатаем каждый уникальный ключ один раз.
                    if (loggedFlycastKeys.add(key)) {
                        LOGGER.info("Flycast queried option (no override): {}", key);
                    }
                    return false;
                }
                Memory m = new Memory(override.length() + 1L);
                m.setString(0, override);
                allocatedOptionMemory.add(m);
                data.setPointer(Native.POINTER_SIZE, m);
                LOGGER.info("Flycast option override: {} = {}", key, override);
                return true;
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

    public boolean isCoreLoaded() {
        return core != null;
    }

    // ======================================================================
    // ===== loadGameImpl (на retro-core-thread) ============================
    // ======================================================================

    private volatile int width;
    private volatile int height;
    private boolean gameLoaded;

    private boolean loadGameImpl(Path romPath) {
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

        this.loadedGameInfo = info;

        if (hwRenderActive && !hwContextResetDone
                && hwContextReset != null && Pointer.nativeValue(hwContextReset) != 0) {
            try {
                if (headlessGl().hlg_make_current() == 0) {
                    LOGGER.error("Failed to make WGL context current before context_reset");
                    teardownHwRender();
                    return false;
                }
                LOGGER.info("Calling context_reset (post-load) @ {}", hwContextReset);
                com.sun.jna.Function.getFunction(hwContextReset).invokeVoid(new Object[0]);
                hwContextResetDone = true;
                LOGGER.info("context_reset completed");
            } catch (Throwable t) {
                LOGGER.error("context_reset crashed post-load: {}", t.getMessage(), t);
                teardownHwRender();
                return false;
            }
        }

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

    private LibretroBridge.RetroGameInfo loadedGameInfo;

    @Override public int getWidth()  { return width; }
    @Override public int getHeight() { return height; }

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

    private void freeGameInfo(LibretroBridge.RetroGameInfo info) {
        this.loadedGameInfo = null;
    }

    private static final long MAX_IN_MEMORY_ROM = 64L * 1024 * 1024;

    private void drainHwFrame() {
        boolean pending;
        int w, h;
        synchronized (frameLock) {
            pending = hwFramePending;
            w = hwFrameW;
            h = hwFrameH;
            hwFramePending = false;
        }
        if (!pending || w <= 0 || h <= 0) return;

        int len = w * h;
        try {
            headlessGl().hlg_make_current();
            int surfW = Math.max(w, hwPbufW);
            int surfH = Math.max(h, hwPbufH);
            if (surfW != hwPbufW || surfH != hwPbufH) {
                headlessGl().hlg_resize(surfW, surfH);
                hwPbufW = surfW;
                hwPbufH = surfH;
            }
            if (hwReadbackBuf == null || hwReadbackCap < len) {
                hwReadbackBuf = new Memory((long) len * 4L);
                hwReadbackCap = len;
            }
            int[] vp = new int[4];
            headlessGl().hlg_read_pixels(vp, hwReadbackBuf, len, w, h);
            synchronized (frameLock) {
                if (frameBuffer.length != len) frameBuffer = new int[len];
                int[] dst = frameBuffer;
                for (int i = 0; i < len; i++) {
                    int px = hwReadbackBuf.getInt((long) i * 4);
                    dst[i] = 0xFF000000 | (px & 0x00FFFFFF);
                }
                newFrame = true;
            }
        } catch (Throwable t) {
            LOGGER.warn("HW readback failed: {}", t.getMessage());
        }
    }

    // ======================================================================
    // ===== runFrameImpl (на retro-core-thread) ============================
    // ======================================================================

    private void runFrameImpl() {
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
                if (hwRenderActive) drainHwFrame();
            } catch (Throwable t) {
                LOGGER.warn("retro_run threw: {}", t.getMessage(), t);
            }
        }
    }

    // ----- pollFrame / input: с любого потока, GL не трогают --------------

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

    // ======================================================================
    // ===== closeImpl (на retro-core-thread) ===============================
    // ======================================================================

    private void closeImpl() {
        if (core == null) {
            LOGGER.debug("close(): core already null — no-op");
            return;
        }
        LOGGER.info("close(): shutting down libretro core {}", corePath);

        if (gameLoaded) {
            try {
                core.retro_unload_game();
                LOGGER.info("retro_unload_game: ok");
            } catch (Throwable t) {
                LOGGER.warn("retro_unload_game failed: {}", t.getMessage());
            }
        }

        if (hwRenderActive && headlessGlReady) {
            try {
                headlessGl().hlg_make_current();
                LOGGER.info("WGL context made current for shutdown");
            } catch (Throwable t) {
                LOGGER.warn("Failed to make WGL current for shutdown: {}", t.getMessage());
            }
        }

        /* HW GL context must be destroyed before retro_deinit (Flycast state machine). */
        destroyHwGlContext();

        if (gameLoaded || coreInitialized) {
            try {
                core.retro_deinit();
                LOGGER.info("retro_deinit: ok");
            } catch (Throwable t) {
                LOGGER.warn("retro_deinit failed: {}", t.getMessage());
            }
        }

        if (isFlycastCore() && supportsHwRender()) {
            resetVehSession();
        }

        teardownHwRender();

        if (headlessGlReady) {
            try { headlessGl().hlg_destroy(); } catch (Throwable ignored) {}
            headlessGlReady = false;
        }

        LibretroBridge closed = this.core;
        KEEP_LOADED.remove(closed);
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
    public String getSaveDir()   { return saveDir; }
}