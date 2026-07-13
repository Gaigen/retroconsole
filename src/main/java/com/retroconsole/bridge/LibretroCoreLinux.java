package com.retroconsole.bridge;

import com.retroconsole.platform.Pcsx2BiosResolver;
import com.retroconsole.platform.VideoQualityPresets;
import com.sun.jna.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;

/** JNA interface to .libheadless_gl.so — multi-instance headless EGL/Mesa GL. */
interface HeadlessGL extends Library {
    HeadlessGL INSTANCE = Native.load(
            Paths.get("config/retroconsole/cores/.libheadless_gl.so").toAbsolutePath().toString(),
            HeadlessGL.class);

    /* Instance lifecycle — handle is the first parameter. */
    Pointer hlg_create();
    void    hlg_free(Pointer h);
    int     hlg_init(Pointer h, int major, int minor);
    int     hlg_init_ex(Pointer h, int api, int major, int minor, int flags);
    void    hlg_destroy(Pointer h);
    void    hlg_release(Pointer h);
    int     hlg_make_current(Pointer h);
    int     hlg_resize(Pointer h, int w, int height);
    void    hlg_read_pixels(Pointer h, int[] viewport, Pointer pixels,
                            int maxPixels, int reqW, int reqH);
    void    hlg_debug_fbo(Pointer h);
    String  hlg_get_gpu_info(Pointer h);
    void    hlg_log_gpu_identity(Pointer h);

    /* Process-wide (no handle): libretro callbacks + log/diagnostics. */
    Pointer hlg_get_proc_address(String sym);
    long    hlg_get_framebuffer();
    Pointer hlg_get_framebuffer_ptr();
    Pointer hlg_get_proc_address_ptr();
    Pointer hlg_get_log_cb_ptr();
    void    hlg_dump_hw_render(Pointer data, int size);
}

/** Callback: unsigned long hlg_get_framebuffer(void) */
interface GetFramebufferCb extends Callback { long invoke(); }

/** Callback: void* hlg_get_proc_address(const char* sym) */
interface GetProcAddressCb extends Callback { Pointer invoke(String sym); }

/** JNA Structure matching struct retro_hw_render_callback (libretro, x86_64). */
class RetroHwRenderCallback extends Structure {
    public int context_type;              // offset  0
    public Pointer context_reset;         // offset  8
    public GetFramebufferCb get_current_framebuffer; // offset 16
    public GetProcAddressCb get_proc_address;        // offset 24
    public byte depth;                    // offset 32
    public byte stencil;                  // offset 33
    public byte bottom_left_origin;       // offset 34
    public int version_major;             // offset 36
    public int version_minor;             // offset 40
    public byte cache_context;            // offset 44
    public Pointer context_destroy;       // offset 48

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("context_type", "context_reset",
                "get_current_framebuffer", "get_proc_address",
                "depth", "stencil", "bottom_left_origin",
                "version_major", "version_minor", "cache_context",
                "context_destroy");
    }
}

/**
 * High-level libretro core wrapper: callback registration,
 * game loading, frame stepping, and a plain int[] ARGB framebuffer.
 */
public class LibretroCoreLinux extends LibretroCore {

    public static LibretroCoreLinux create(Path corePath, String systemDir, String saveDir) {
        return new LibretroCoreLinux(corePath, systemDir, saveDir);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroCore");

    private LibretroBridge core;
    private final java.nio.file.Path corePath;
    private LibretroBridge.RetroEnvironment envCallback;
    private LibretroBridge.RetroVideoRefresh videoCallback;
    private LibretroBridge.RetroAudioSample audioCallback;
    private LibretroBridge.RetroAudioSampleBatch audioBatchCallback;
    private LibretroBridge.RetroInputPoll inputPollCallback;
    private LibretroBridge.RetroInputState inputStateCallback;

    // Framebuffer — written by video callback, read by pollFrame()
    private final Object frameLock = new Object();
    private int[] frameBuffer;
    private int frameWidth;
    private int frameHeight;
    private volatile boolean newFrame = false;

    private int pixelFormat = LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888;

    // HW render state
    private boolean hwRenderActive = false;
    private boolean hwGpuLoggedOnEmulatorThread = false;
    private boolean hwBottomLeftOrigin = true;
    private boolean headlessGlReady = false;
    private int headlessGlApi = -1;
    private int hwPbufW = 0;
    private int hwPbufH = 0;
    private Pointer hwContextReset = null;
    private boolean hwContextResetDone = false;

    /** Handle of our GL instance in .libheadless_gl.so (one per core). */
    private Pointer glCtx = null;

    private static final java.util.List<Object> PINNED_HW_CALLBACKS = new java.util.ArrayList<>();

    private final GetFramebufferCb hwGetFbCb = () -> {
        if (glCtx != null) HeadlessGL.INSTANCE.hlg_make_current(glCtx);
        return HeadlessGL.INSTANCE.hlg_get_framebuffer();
    };
    private final GetProcAddressCb hwGetProcCb = sym -> {
        Pointer p = HeadlessGL.INSTANCE.hlg_get_proc_address(sym);
        if (p == null || Pointer.nativeValue(p) == 0) {
            LOGGER.warn("get_proc_address -> NULL for symbol: {}", sym);
            return Pointer.NULL;
        }
        return p;
    };

    private LibretroBridge.RetroLogCallback logCallback;
    private LibretroBridge.RetroLogCallbackStruct logCallbackStruct;
    private static final Pointer RETRO_HW_FRAME_BUFFER_VALID = Pointer.createConstant(-1);

    // Audio pacing (Flycast uses audio consumption as timing)
    private final AudioPacing audioPacing = new AudioPacing(44100);
    private static final int AUDIO_RING_CAP = 48000 * 2;
    private final short[] audioRing = new short[AUDIO_RING_CAP];
    private int audioWrite;
    private int audioCount;
    private final Object audioLock = new Object();
    private short[] audioBulkScratch = new short[4096];
    private volatile double audioSampleRate = 48000.0;
    private volatile double timingFps = 60.0;

    // Input — written by server thread, read by emulator thread (lock-free)
    private final java.util.concurrent.atomic.AtomicIntegerArray joypadState =
            new java.util.concurrent.atomic.AtomicIntegerArray(21);
    private final java.util.concurrent.atomic.AtomicIntegerArray analogState =
            new java.util.concurrent.atomic.AtomicIntegerArray(4);
    private final java.util.concurrent.atomic.AtomicIntegerArray triggerState =
            new java.util.concurrent.atomic.AtomicIntegerArray(2);
    private volatile short pointerX;
    private volatile short pointerY;
    private volatile boolean pointerPressed;
    private int pointerPollLogLeft = 10;

    // System paths (persistent native memory for env callbacks)
    private String systemDir;
    private String saveDir;
    private Memory persistentSystemDir;
    private Memory persistentSaveDir;
    private Memory persistentLibretroPath;
    private boolean gameLoaded = false;
    private volatile boolean pendingBatteryLoad;
    private Path pendingBatteryRomPath;

    private String loadedGamePath;

    private LibretroCoreLinux(Path corePath, String systemDir, String saveDir) {
        super(corePath);
        this.corePath = corePath;              // FIX: was not assigned → NPE in isXxxCore()
        LOGGER.info("Loading libretro core: {}", corePath);

        if (systemDir != null)
            systemDir = Path.of(systemDir).toAbsolutePath().normalize().toString();
        if (saveDir != null)
            saveDir = Path.of(saveDir).toAbsolutePath().normalize().toString();
        this.systemDir = systemDir;
        this.saveDir = saveDir;
        updatePersistentDirMemory();

        this.core = Native.load(corePath.toAbsolutePath().toString(), LibretroBridge.class);
        LOGGER.info("Core loaded. API version: {}", this.core.retro_api_version());

        setupCallbacks(); // BEFORE retro_init()

        if (isFlycastCore()) disableFlycastLogCall();
        if (isPpssppCore()) seedPpssppDefaults();
        if (isPcsx2Core()) seedPcsx2Defaults();
        if (isFlycastCore()) seedFlycastDefaults();
        if (isPcsxRearmedCore()) seedPcsxRearmedDefaults();
        if (isMelondsCore()) seedMelondsDefaults();
        if (isCitraCore()) seedCitraDefaults();

        this.core.retro_init();
        LOGGER.info("Core initialized.");

        var sysInfo = new LibretroBridge.RetroSystemInfo();
        this.core.retro_get_system_info(sysInfo);
        LOGGER.info("Core: {} v{}", sysInfo.library_name, sysInfo.library_version);
    }

    private void setupCallbacks() {
        // Environment — core requests
        envCallback = (cmd, data) -> handleEnvironment(cmd, data);
        core.retro_set_environment(envCallback);

        // Video — frames from core
        videoCallback = (data, width, height, pitch) -> {
            if (width <= 0 || height <= 0) {
                if (hwRenderActive) newFrame = true;
                return;
            }
            synchronized (frameLock) {
                long dataAddr = data != null ? Pointer.nativeValue(data) : 0;
                boolean hwFb = hwRenderActive && (data == null || dataAddr == -1 || dataAddr == 0);
                // GET_CAN_DUPE: NULL data = duplicate previous frame, not a pixel buffer.
                if (!hwFb && (data == null || dataAddr == 0)) {
                    newFrame = true;
                    return;
                }
                if (frameBuffer == null || frameWidth != width || frameHeight != height) {
                    frameBuffer = new int[width * height];
                    frameWidth = width;
                    frameHeight = height;
                }
                int[] fb = frameBuffer;
                if (hwFb) {
                    // PPSSPP/PCSX2: do not read until context_reset on emulator thread
                    if ((isPpssppCore() || isPcsx2Core() || isCitraCore()) && !hwContextResetDone) {
                        newFrame = true;
                        return;
                    }
                    readbackHwFrame(fb, width, height);
                    newFrame = true;
                    return;
                }
                if (data == null) return;
                convertSoftwareFrame(fb, data, width, height, pitch);
                newFrame = true;
            }
        };
        core.retro_set_video_refresh(videoCallback);

        // Audio — audio production = timing signal (Flycast)
        audioCallback = (left, right) -> { /* discard */ };
        core.retro_set_audio_sample(audioCallback);

        audioBatchCallback = (data, frames) -> {
            int frameCount = (int) frames;
            if (frameCount > 0 && data != null) {
                synchronized (audioLock) {
                    appendAudio(data, frameCount * 2);
                }
            }
            audioPacing.consumeSamples(frameCount);
            return frames;
        };
        core.retro_set_audio_sample_batch(audioBatchCallback);

        inputPollCallback = () -> { /* state already set by server thread */ };
        core.retro_set_input_poll(inputPollCallback);

        inputStateCallback = (port, device, index, id) -> {
            if (port != 0) return 0;
            if (device == LibretroBridge.RETRO_DEVICE_JOYPAD) {
                if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_MASK) {
                    int mask = 0;
                    for (int i = 0; i < 21; i++)
                        if (joypadState.get(i) != 0) mask |= (1 << i);
                    return (short) mask;
                }
                if (index == 0 && id >= 0 && id < 21)
                    return (short) joypadState.get(id);
            }
            if (device == LibretroBridge.RETRO_DEVICE_POINTER) {
                if (pointerPollLogLeft > 0) {
                    pointerPollLogLeft--;
                    LOGGER.info("POINTER poll id={} -> x={} y={} pressed={}",
                            id, pointerX, pointerY, pointerPressed);
                }
                return switch (id) {
                    case LibretroBridge.RETRO_DEVICE_ID_POINTER_X -> pointerX;
                    case LibretroBridge.RETRO_DEVICE_ID_POINTER_Y -> pointerY;
                    case LibretroBridge.RETRO_DEVICE_ID_POINTER_PRESSED ->
                            (short) (pointerPressed ? 1 : 0);
                    case LibretroBridge.RETRO_DEVICE_ID_POINTER_COUNT ->
                            (short) (pointerPressed ? 1 : 0);
                    default -> 0;
                };
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
        core.retro_set_input_state(inputStateCallback);
    }

    /** Read HW frame from headless GL into fb (RGBA→ARGB, Y-flip by origin). */
    private void readbackHwFrame(int[] fb, int width, int height) {
        try {
            // GL surface must fit the core viewport
            int surfW = Math.max(width, hwPbufW);
            int surfH = Math.max(height, hwPbufH);
            if (surfW != hwPbufW || surfH != hwPbufH) {
                HeadlessGL.INSTANCE.hlg_resize(glCtx, surfW, surfH);
                hwPbufW = surfW;
                hwPbufH = surfH;
            }
            HeadlessGL.INSTANCE.hlg_debug_fbo(glCtx);
            int[] vp = new int[4];
            int maxPixels = width * height;
            Memory nativePixels = new Memory((long) maxPixels * 4L);
            HeadlessGL.INSTANCE.hlg_read_pixels(glCtx, vp, nativePixels, maxPixels, width, height);

            // Grow GL storage if core renders to a larger viewport (PCSX2/Flycast)
            int vpW = vp[2] > 0 ? vp[2] : width;
            int vpH = vp[3] > 0 ? vp[3] : height;
            if (vpW > hwPbufW || vpH > hwPbufH) {
                hwPbufW = Math.max(hwPbufW, vpW);
                hwPbufH = Math.max(hwPbufH, vpH);
                HeadlessGL.INSTANCE.hlg_resize(glCtx, hwPbufW, hwPbufH);
            }
            for (int y = 0; y < height; y++) {
                int srcY = hwBottomLeftOrigin ? (height - 1 - y) : y;
                int dstRow = y * width;
                long srcRow = (long) srcY * width * 4;
                for (int x = 0; x < width; x++) {
                    long off = srcRow + (long) x * 4;
                    int r = nativePixels.getByte(off)     & 0xFF;
                    int g = nativePixels.getByte(off + 1) & 0xFF;
                    int b = nativePixels.getByte(off + 2) & 0xFF;
                    fb[dstRow + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        } catch (Throwable t) {
            Arrays.fill(fb, 0xFF202020); // dark gray — visible when readback failed
        }
    }

    /** Convert software frame to ARGB using current pixelFormat. */
    private void convertSoftwareFrame(int[] fb, Pointer data, int width, int height, long pitch) {
        switch (pixelFormat) {
            case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888 -> {
                int stride = (int) (pitch / 4);
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++)
                        fb[y * width + x] = 0xFF000000 | (data.getInt((long) (y * stride + x) * 4) & 0x00FFFFFF);
            }
            case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565 -> {
                int stride = (int) (pitch / 2);
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++) {
                        short px = data.getShort((long) (y * stride + x) * 2);
                        int r = ((px >> 11) & 0x1F) << 3;
                        int g = ((px >> 5)  & 0x3F) << 2;
                        int b =  (px        & 0x1F) << 3;
                        fb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
            }
            case LibretroBridge.RETRO_PIXEL_FORMAT_0RGB1555 -> {
                int stride = (int) (pitch / 2);
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++) {
                        short px = data.getShort((long) (y * stride + x) * 2);
                        int r = ((px >> 10) & 0x1F) << 3;
                        int g = ((px >> 5)  & 0x1F) << 3;
                        int b =  (px        & 0x1F) << 3;
                        fb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
            }
        }
    }

    
    // NOP call sites in flycast_libretro.so that dereference the internal
    // log_cb (JNA passes 0x4 garbage instead of a pointer → SIGSEGV). Offsets valid
    // for the flycast_libretro.so build in this workspace (md5 209786943fee54e07b4c0849d84907ad).
    private static final int FLYCAST_LOG_CALL_OFFSET = 0x39abad;  // call rcx in LogManager::LogWithFullPath (2 bytes)
    private static final int FLYCAST_BSS_CALL_1      = 0x397b08;  // call [0x26d82e0] in retro_load_game (6 bytes)
    private static final int FLYCAST_BSS_CALL_2      = 0x3985c7;  // call [0x26d82e0] in retro_load_game (6 bytes)
    private static final int FLYCAST_BSS_CALL_3      = 0x399f28;  // call [0x26d82e0] in retro_init/other (6 bytes)
    private static final byte[] NOP2 = { (byte) 0x90, (byte) 0x90 };
    private static final byte[] NOP6 = { (byte) 0x90, (byte) 0x90, (byte) 0x90,
                                         (byte) 0x90, (byte) 0x90, (byte) 0x90 };

    private interface LibC extends com.sun.jna.Library {
        LibC INSTANCE = com.sun.jna.Native.load("c", LibC.class);
        int mprotect(Pointer addr, long len, int prot);
        int PROT_READ  = 0x1;
        int PROT_WRITE = 0x2;
        int PROT_EXEC  = 0x4;
    }

    /** Disable Flycast broken logging. Call BEFORE retro_init(). */
    private void disableFlycastLogCall() {
        long baseAddr = 0;
        try {
            String mapsPath = "/proc/" + ProcessHandle.current().pid() + "/maps";
            for (String line : Files.readAllLines(Paths.get(mapsPath))) {
                if (line.contains("flycast_libretro.so") && line.contains("r-xp")) {
                    baseAddr = Long.parseLong(line.split("-")[0], 16);
                    break;
                }
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to read /proc/self/maps", t);
            return;
        }
        if (baseAddr == 0) {
            LOGGER.debug("flycast_libretro.so not loaded — skipping Flycast patches");
            return;
        }
        nopFlycastCallSite(baseAddr, FLYCAST_LOG_CALL_OFFSET, NOP2);
        nopFlycastCallSite(baseAddr, FLYCAST_BSS_CALL_1, NOP6);
        nopFlycastCallSite(baseAddr, FLYCAST_BSS_CALL_2, NOP6);
        nopFlycastCallSite(baseAddr, FLYCAST_BSS_CALL_3, NOP6);
    }

    private void nopFlycastCallSite(long baseAddr, int offset, byte[] nopBytes) {
        long pageSize = 4096;
        long targetAddr = baseAddr + offset;
        long pageStart = targetAddr & ~(pageSize - 1);
        long pageEnd   = (targetAddr + nopBytes.length + pageSize - 1) & ~(pageSize - 1);
        int rc = LibC.INSTANCE.mprotect(new Pointer(pageStart), pageEnd - pageStart,
                LibC.PROT_READ | LibC.PROT_WRITE | LibC.PROT_EXEC);
        if (rc != 0) {
            LOGGER.error("mprotect failed for Flycast patch at base+0x{} (rc={})",
                    Long.toHexString(offset), rc);
            return;
        }
        Pointer p = new Pointer(targetAddr);
        byte[] original = p.getByteArray(0, nopBytes.length);
        p.write(0, nopBytes, 0, nopBytes.length);
        byte[] verify = p.getByteArray(0, nopBytes.length);
        boolean ok = true;
        for (byte b : verify) if (b != (byte) 0x90) { ok = false; break; }
        LOGGER.info("Flycast patch at base+0x{} ({} bytes): original={} verified={}",
                Long.toHexString(offset), nopBytes.length, bytesToHex(original), ok);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x & 0xFF));
        return sb.toString().trim();
    }

    
    private boolean coreNameContains(String needle) {
        return corePath != null
                && corePath.getFileName().toString().toLowerCase().contains(needle);
    }
    private boolean isFlycastCore()     { return coreNameContains("flycast"); }
    private boolean isPcsxRearmedCore() { return coreNameContains("pcsx_rearmed"); }
    private boolean isPpssppCore()      { return coreNameContains("ppsspp"); }
    private boolean isMelondsCore()     { return coreNameContains("melonds") || coreNameContains("desmume"); }
    private boolean isCitraCore()       { return coreNameContains("citra"); }

    /** Headless EGL (Mesa) for HW-render cores. */
    private boolean supportsHwRender() { return true; }

    /** PPSSPP on Linux uses GLEW — needs desktop GL compat, not EGL GLES. */
    private static final int HLG_FLAG_COMPAT_PROFILE = 1;

    /** Lazily create/reinit our GL instance for the core context type. */
    private boolean ensureHeadlessGl(int ctxType) {
        int api, major, minor, flags;
        if (isPpssppCore()) {
            api = 0; major = 3; minor = 3; flags = HLG_FLAG_COMPAT_PROFILE;
        } else {
            api = (ctxType == 1 || ctxType == 2 || ctxType == 4) ? 1 : 0;
            major = 3;
            minor = (api == 1) ? 0 : (ctxType == 3 ? 3 : 1);
            flags = 0;
        }
        int profileKey = (api & 0xFF) | ((flags & 0xFF) << 8) | ((minor & 0xFF) << 16);
        if (headlessGlReady && headlessGlApi == profileKey) return true;

        if (glCtx == null) {
            glCtx = HeadlessGL.INSTANCE.hlg_create();
            if (glCtx == null || Pointer.nativeValue(glCtx) == 0) {
                LOGGER.error("hlg_create() failed — no headless GL instance");
                glCtx = null;
                return false;
            }
        }
        if (headlessGlReady) {
            try { HeadlessGL.INSTANCE.hlg_destroy(glCtx); } catch (Throwable ignored) {}
            headlessGlReady = false;
            headlessGlApi = -1;
        }
        try {
            int glOk = HeadlessGL.INSTANCE.hlg_init_ex(glCtx, api, major, minor, flags);
            headlessGlReady = glOk != 0;
            if (headlessGlReady) headlessGlApi = profileKey;
            String apiName = isPpssppCore() ? "GL 3.3 Compat"
                    : (api == 1 ? "GLES" + major : "GL " + major + "." + minor);
            LOGGER.info("Headless GL context ({}): {}", apiName, headlessGlReady ? "OK" : "FAILED");
            if (headlessGlReady)
                LOGGER.info("Headless GL GPU: {}", HeadlessGL.INSTANCE.hlg_get_gpu_info(glCtx));
            return headlessGlReady;
        } catch (Throwable t) {
            LOGGER.warn("Headless GL init failed: {}", t.getMessage());
            return false;
        }
    }

    private void seedPpssppDefaults() {
        VideoQualityPresets.seedPpsspp(this::registerCoreOption);
        registerCoreOption("ppsspp_lower_resolution_for_effects", "Balanced");
        LOGGER.info("PPSSPP defaults: 960x544 (2x native, headless-friendly)");
    }

    private void seedPcsx2Defaults() {
        VideoQualityPresets.seedPcsx2(this::registerCoreOption);
        LOGGER.info("PCSX2 defaults: OpenGL 3x upscale");
    }

    private void seedFlycastDefaults() {
        VideoQualityPresets.seedFlycast(this::registerCoreOption);
        LOGGER.info("Flycast defaults: 1920x1440 + tex upscale 4x");
    }

    private void seedMelondsDefaults() {
        VideoQualityPresets.seedMelonds(this::registerCoreOption);
        LOGGER.info("melonDS defaults: Top/Bottom software renderer");
    }

    private void seedCitraDefaults() {
        VideoQualityPresets.seedCitra(this::registerCoreOption);
        LOGGER.info("Citra defaults: OpenGL Top-Bottom layout");
    }

    /**
     * Seeds shared Citra system files from {@code system/citra/} into the per-player Citra folder.
     * Copies only when missing or size differs (dump updates). Player saves ({@code nand/data}, {@code sdmc})
     * are untouched — they are not present in the shared folder.
     */
    private void seedSharedCitraFiles() {
        if (systemDir == null || saveDir == null) {
            return;
        }
        Path shared = Path.of(systemDir).resolve("citra");
        Path userDir = Path.of(saveDir).resolve("Citra");
        if (!Files.isDirectory(shared)) {
            return;
        }
        try (var walk = Files.walk(shared)) {
            walk.filter(Files::isRegularFile).forEach(src -> {
                Path rel = shared.relativize(src);
                Path dst = userDir.resolve(rel);
                try {
                    if (Files.exists(dst) && Files.size(dst) == Files.size(src)) {
                        return;
                    }
                    Files.createDirectories(dst.getParent());
                    try {
                        Files.deleteIfExists(dst);
                        Files.createLink(dst, src);
                    } catch (Exception linkFail) {
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                    LOGGER.info("Seeded shared Citra file: {}", rel);
                } catch (IOException e) {
                    LOGGER.warn("Failed to seed shared Citra file {}: {}", rel, e.toString());
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to walk shared Citra dir: {}", e.toString());
        }
    }

    private void seedPcsxRearmedDefaults() {
        registerCoreOption("pcsx_rearmed_memcard1", "libretro");
        registerCoreOption("pcsx_rearmed_memcard2", "shared");
        registerCoreOption("pcsx_rearmed_neon_enhancement_enable", "enabled");
        registerCoreOption("pcsx_rearmed_gpu_unai_scale_hires", "enabled");
    }

    /** Set directories cores request via env callback. */
    public void setDirectories(String systemDir, String saveDir) {
        this.systemDir = systemDir;
        this.saveDir = saveDir;
        updatePersistentDirMemory();
    }

    private void updatePersistentDirMemory() {
        if (systemDir != null) {
            persistentSystemDir = new Memory(systemDir.length() + 1);
            persistentSystemDir.setString(0, systemDir);
        }
        if (saveDir != null) {
            persistentSaveDir = new Memory(saveDir.length() + 1);
            persistentSaveDir.setString(0, saveDir);
        }
        if (corePath != null) {
            String p = corePath.toAbsolutePath().normalize().toString();
            persistentLibretroPath = new Memory(p.length() + 1);
            persistentLibretroPath.setString(0, p);
        }
    }

    // =====================================================================
    
    // =====================================================================

    private boolean handleEnvironment(int cmd, Pointer data) {
        if (cmd == 0x1002f) { // GET_SAVESTATE_CONTEXT and other raw requests
            if (data != null) data.setInt(0, 3);
            return true;
        }
        int base = LibretroEnvironment.normalize(cmd);
        switch (base) {
            case LibretroEnvironment.GET_CAN_DUPE -> {
                if (data != null) data.setByte(0, (byte) 1);
                return true;
            }
            case LibretroEnvironment.GET_SYSTEM_DIRECTORY -> {
                if (data != null && persistentSystemDir != null) {
                    data.setPointer(0, persistentSystemDir);
                    LOGGER.info("Core requested GET_SYSTEM_DIRECTORY -> {}", systemDir);
                    logPcsx2BiosDirIfNeeded();
                    return true;
                }
                LOGGER.warn("Core requested GET_SYSTEM_DIRECTORY but systemDir is null");
                return false;
            }
            case LibretroEnvironment.GET_LIBRETRO_PATH -> {
                if (data != null && persistentLibretroPath != null) {
                    data.setPointer(0, persistentLibretroPath);
                    return true;
                }
                return false;
            }
            case LibretroEnvironment.SET_SUPPORT_NO_GAME -> {
                if (data != null) data.setByte(0, (byte) 0);
                return true;
            }
            case LibretroEnvironment.GET_SAVE_DIRECTORY -> {
                if (data != null && persistentSaveDir != null) {
                    data.setPointer(0, persistentSaveDir);
                    return true;
                }
                return false;
            }
            case LibretroEnvironment.SET_PIXEL_FORMAT -> {
                if (data == null) return false;
                pixelFormat = data.getInt(0);
                LOGGER.info("Core set pixel format: {}", switch (pixelFormat) {
                    case LibretroBridge.RETRO_PIXEL_FORMAT_0RGB1555 -> "0RGB1555";
                    case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888 -> "XRGB8888";
                    case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565 -> "RGB565";
                    default -> "UNKNOWN(" + pixelFormat + ")";
                });
                return true;
            }
            case LibretroEnvironment.SET_HW_RENDER -> {
                if (data == null) return false;
                try {
                    return handleSetHwRender(data);
                } catch (Throwable t) {
                    LOGGER.error("Failed to handle SET_HW_RENDER", t);
                    return false;
                }
            }
            case LibretroEnvironment.SET_INPUT_DESCRIPTORS -> {
                return true;
            }
            case LibretroEnvironment.SET_VARIABLES -> {
                LOGGER.info("Core requesting SET_VARIABLES");
                if (data != null) handleSetVariables(data);
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS -> {
                LOGGER.info("Core requesting SET_CORE_OPTIONS");
                if (data != null) handleSetCoreOptions(data);
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS_V2 -> {
                LOGGER.info("Core requesting SET_CORE_OPTIONS_V2");
                if (data != null) handleSetCoreOptionsV2(data);
                return true;
            }
            case LibretroEnvironment.GET_VARIABLE -> {
                return data != null && handleGetVariable(data);
            }
            case LibretroEnvironment.GET_VARIABLE_UPDATE -> {
                if (data != null) data.setByte(0, (byte) 0);
                return true;
            }
            case LibretroEnvironment.SET_AUDIO_CALLBACK -> {
                LOGGER.info("Core requesting SET_AUDIO_CALLBACK — accepting");
                return true;
            }
            case LibretroEnvironment.GET_RUMBLE_INTERFACE -> {
                return false;
            }
            case LibretroEnvironment.GET_LOG_INTERFACE -> {
                if (isFlycastCore()) return false; // Flycast: logging disabled by NOP patches
                if (data == null) return false;
                /* Native variadic log callback from headless_gl.so: JNA cannot bind
                 * retro_log_printf_t (variadic), so we write the C pointer directly —
                 * real core logs instead of "%s %s". */
                try {
                    Pointer nativeLogCb = HeadlessGL.INSTANCE.hlg_get_log_cb_ptr();
                    if (nativeLogCb != null && Pointer.nativeValue(nativeLogCb) != 0) {
                        data.setPointer(0, nativeLogCb);
                        return true;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Native log cb unavailable, falling back to JNA: {}", t.getMessage());
                }
                if (logCallback == null) {
                    logCallback = (level, fmt) -> {
                        String msg = fmt != null ? fmt.trim() : "";
                        switch (level) {
                            case 0 -> LOGGER.error("[core] {}", msg);
                            case 1 -> LOGGER.warn("[core] {}", msg);
                            case 2 -> LOGGER.info("[core] {}", msg);
                            default -> LOGGER.debug("[core] {}", msg);
                        }
                    };
                    logCallbackStruct = new LibretroBridge.RetroLogCallbackStruct();
                    logCallbackStruct.log = logCallback;
                    logCallbackStruct.write();
                }
                data.write(0, logCallbackStruct.getPointer().getByteArray(0, Native.POINTER_SIZE),
                        0, Native.POINTER_SIZE);
                return true;
            }
            case LibretroEnvironment.SET_SYSTEM_AV_INFO -> {
                if (data != null) {
                    int w = data.getInt(0);
                    int h = data.getInt(4);
                    double fps = data.getDouble(24);
                    double sr = data.getDouble(32);
                    if (w > 0 && h > 0) {
                        synchronized (frameLock) {
                            frameWidth = w;
                            frameHeight = h;
                        }
                    }
                    if (fps > 1.0 && fps < 1000.0) timingFps = fps;
                    if (sr > 8000.0 && sr < 384000.0) {
                        audioSampleRate = sr;
                        audioPacing.setSampleRate((int) Math.round(sr));
                    }
                    LOGGER.info("SET_SYSTEM_AV_INFO: {}x{} @ {} fps, {} Hz", w, h, fps, sr);
                }
                return true;
            }
            case LibretroEnvironment.SET_CONTROLLER_INFO -> {
                return true;
            }
            case LibretroEnvironment.SET_GEOMETRY -> {
                if (data != null) {
                    int baseW = data.getInt(0);
                    int baseH = data.getInt(4);
                    LOGGER.info("Core set geometry: {}x{}", baseW, baseH);
                    if (baseW > 0 && baseH > 0) {
                        synchronized (frameLock) {
                            frameWidth = baseW;
                            frameHeight = baseH;
                            int needed = baseW * baseH;
                            if (frameBuffer == null || frameBuffer.length != needed)
                                frameBuffer = new int[needed];
                        }
                        if (hwRenderActive) resizeHwFramebuffer(baseW, baseH);
                    }
                }
                return true;
            }
            case LibretroEnvironment.GET_USERNAME -> {
                return false;
            }
            case LibretroEnvironment.GET_LANGUAGE -> {
                if (data != null) data.setInt(0, 0); // RETRO_LANGUAGE_ENGLISH
                return true;
            }
            case LibretroEnvironment.GET_MESSAGE_INTERFACE_VERSION -> {
                if (data != null) data.setInt(0, 1);
                return true;
            }
            case LibretroEnvironment.GET_CORE_OPTIONS_VERSION -> {
                if (data != null) data.setInt(0, 2);
                return true;
            }
            case LibretroEnvironment.GET_PREFERRED_HW_RENDER -> {
                if (data != null && supportsHwRender()) {
                    int preferred = isPpssppCore() ? 1 : LibretroEnvironment.RETRO_HW_CONTEXT_OPENGL_CORE;
                    data.setInt(0, preferred);
                    LOGGER.info("Core requested GET_PREFERRED_HW_RENDER -> {}",
                            isPpssppCore() ? "OPENGL (desktop compat)"
                                    : (preferred == 1 ? "OPENGL/GLES" : "OPENGL_CORE"));
                    return true;
                }
                return false;
            }
            case 51 -> { // GET_INPUT_BITMASKS — JOYPAD_MASK implemented in input_state
                return true;
            }
            case LibretroEnvironment.SET_SERIALIZATION_QUIRKS -> {
                if ((cmd & LibretroEnvironment.EXPERIMENTAL) != 0) {
                    if (supportsHwRender()) {
                        LOGGER.info("SET_HW_SHARED_CONTEXT -> true (citra, raw=0x{})",
                                Integer.toHexString(cmd));
                        return true;
                    }
                    return false;
                }
                return true;
            }
            case LibretroEnvironment.SET_PERFORMANCE_LEVEL -> {
                return true;
            }
            case LibretroEnvironment.GET_DISK_CONTROL_INTERFACE_VERSION -> {
                if (data != null) data.setInt(0, 0);
                return true;
            }
            case LibretroEnvironment.SET_DISK_CONTROL_EXT_INTERFACE -> {
                return true;
            }
            case LibretroEnvironment.SET_AUDIO_BUFFER_STATUS_CALLBACK -> {
                return true;
            }
            case 36 -> { // SET_MEMORY_MAPS
                return true;
            }
            case 40 -> { // GET_CURRENT_SOFTWARE_FRAMEBUFFER
                return false;
            }
            case LibretroEnvironment.SET_MESSAGE_EXT -> {
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS_V2_INTL -> {
                LOGGER.info("Core requesting SET_CORE_OPTIONS_V2_INTL");
                if (data != null) handleSetCoreOptionsV2Intl(data);
                return true;
            }
            case 45 -> { // GET_VFS_INTERFACE
                return false;
            }
            case LibretroEnvironment.SET_MESSAGE -> {
                return false;
            }
            case LibretroEnvironment.SET_DISK_CONTROL_INTERFACE -> {
                return false;
            }
            case LibretroEnvironment.SET_KEYBOARD_CALLBACK -> {
                return false;
            }
            case LibretroEnvironment.SET_SUBSYSTEM_INFO -> {
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS_DISPLAY -> {
                return true;
            }
            case LibretroEnvironment.SET_MINIMUM_AUDIO_LATENCY -> {
                return true;
            }
            case LibretroEnvironment.GET_AUDIO_VIDEO_ENABLE -> {
                if (data != null) data.setInt(0, 3);
                return true;
            }
            case LibretroEnvironment.SET_FASTFORWARDING_OVERRIDE -> {
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK -> {
                return true;
            }
            case LibretroEnvironment.SET_VARIABLE -> {
                if (data == null) return false;
                Pointer keyPtr = data.getPointer(0);
                if (keyPtr == null) return false;
                String key = keyPtr.getString(0);
                if (key == null || key.isEmpty()) return false;
                Pointer valPtr = data.getPointer(Native.POINTER_SIZE);
                String val = valPtr != null ? valPtr.getString(0) : "";
                if (key.startsWith("ppsspp_"))
                    val = applyPpssppDefault(key, val != null ? val : "");
                coreOptions.put(key, val != null ? val : "");
                LOGGER.debug("Core SET_VARIABLE: {} = {}", key, val);
                return true;
            }
            case 42 -> { // SET_SUPPORT_ACHIEVEMENTS (experimental)
                return true;
            }
            default -> {
                long dataAddr = data != null ? Pointer.nativeValue(data) : 0;
                LOGGER.warn("Unhandled env {} (raw=0x{}, base={}) data=0x{}",
                        LibretroEnvironment.name(cmd), Integer.toHexString(cmd),
                        LibretroEnvironment.normalize(cmd), Long.toHexString(dataAddr));
                return false;
            }
        }
    }

    /** SET_HW_RENDER: our GL instance + retro_hw_render_callback setup. */
    private boolean handleSetHwRender(Pointer data) {
        int ctxType = data.getInt(0);
        String ctxName = switch (ctxType) {
            case 0 -> "NONE";
            case 1 -> "OPENGL";
            case 2 -> "OPENGLES2";
            case 3 -> "OPENGL_CORE";
            case 4 -> "OPENGLES3";
            case 5 -> "OPENGLES_VERSION";
            case 6 -> "VULKAN";
            default -> "UNKNOWN(" + ctxType + ")";
        };
        LOGGER.info("Core requests HW render: context_type={} ({})", ctxType, ctxName);

        if (ctxType == 0) {
            LOGGER.info("HW render NONE accepted (software renderer)");
            hwRenderActive = false;
            return true;
        }
        if (!supportsHwRender()) {
            LOGGER.info("Rejecting {} — no HW render bridge", ctxName);
            return false;
        }
        // Mesa: OpenGL / OpenGL Core / GLES3 (no Vulkan). Check BEFORE init —
        // do not create a context we will reject immediately.
        if (ctxType != 1 && ctxType != 3 && ctxType != 4) {
            LOGGER.info("Rejecting {} — only OpenGL contexts supported", ctxName);
            return false;
        }
        if (!ensureHeadlessGl(ctxType)) {
            LOGGER.info("Rejecting {} — headless EGL init failed", ctxName);
            return false;
        }

        int glMajor = 3;
        int glMinor = switch (ctxType) {
            case 3 -> 3; // OPENGL_CORE — PCSX2 expects 3.3+
            default -> isPpssppCore() ? 3 : 0;
        };

        HeadlessGL.INSTANCE.hlg_dump_hw_render(data, 80);

        pinHwCallbacks();
        Pointer fbFn = CallbackReference.getFunctionPointer(hwGetFbCb);
        Pointer procFn = CallbackReference.getFunctionPointer(hwGetProcCb);
        LOGGER.info("  hw callbacks: get_framebuffer @ {}, get_proc_address @ {}", fbFn, procFn);
        data.setPointer(16, fbFn);
        data.setPointer(24, procFn);
        data.setInt(36, glMajor);
        data.setInt(40, glMinor);
        data.setByte(44, (byte) 1);

        HeadlessGL.INSTANCE.hlg_dump_hw_render(data, 80);

        hwContextReset = data.getPointer(8);
        hwContextResetDone = false;
        hwBottomLeftOrigin = data.getByte(34) != 0;

        // Flycast: context_reset on init thread. PPSSPP/PCSX2 — on emulator thread (see runFrame).
        if (isFlycastCore()) {
            if (hwContextReset != null && Pointer.nativeValue(hwContextReset) != 0) {
                LOGGER.info("  Calling context_reset @ {} to init GL function table", hwContextReset);
                com.sun.jna.Function.getFunction(hwContextReset).invokeVoid(new Object[0]);
                LOGGER.info("  context_reset completed");
            } else {
                LOGGER.warn("  context_reset is NULL!");
            }
        }

        hwRenderActive = true;
        LOGGER.info("HW render context provided for {} (headless EGL, ctx={})", ctxName, glCtx);
        return true;
    }

    private void pinHwCallbacks() {
        for (Object cb : new Object[] { hwGetFbCb, hwGetProcCb }) {
            if (cb != null && !PINNED_HW_CALLBACKS.contains(cb)) {
                PINNED_HW_CALLBACKS.add(cb);
            }
        }
    }

    private void resizeHwFramebuffer(int w, int h) {
        if (!headlessGlReady || glCtx == null || w <= 0 || h <= 0) return;
        try {
            if (HeadlessGL.INSTANCE.hlg_make_current(glCtx) == 0) return;
            if (w != hwPbufW || h != hwPbufH) {
                HeadlessGL.INSTANCE.hlg_resize(glCtx, w, h);
                hwPbufW = w;
                hwPbufH = h;
                LOGGER.info("HW offscreen FBO resized to {}x{}", w, h);
            }
        } catch (Throwable t) {
            LOGGER.warn("HW FBO resize failed: {}", t.getMessage());
        }
    }

    private int hwFboDim(LibretroBridge.RetroSystemAVInfo av, boolean width) {
        synchronized (frameLock) {
            int live = width ? frameWidth : frameHeight;
            if (live > 0) return live;
        }
        return avMaxDim(av, width);
    }

    private static int avMaxDim(LibretroBridge.RetroSystemAVInfo av, boolean width) {
        int base = width ? av.geometry.base_width : av.geometry.base_height;
        int max = width ? av.geometry.max_width : av.geometry.max_height;
        if (base > 0 && max > base * 3) return base;
        return Math.max(max > 0 ? max : 0, base > 0 ? base : 0);
    }

    // =====================================================================
    //  Core options
    // =====================================================================

    /** Core variable keys/values — answers to GET_VARIABLE. */
    private final java.util.Map<String, String> coreOptions = new java.util.LinkedHashMap<>();
    /** Keep Memory alive: GC must not free strings before the core reads them. */
    private final java.util.List<Memory> allocatedVarMemory = new java.util.ArrayList<>();

    private static final int CORE_OPT_VALUE_SIZE = Native.POINTER_SIZE * 2;
    private static final int CORE_OPT_DEF_V1_SIZE =
            Native.POINTER_SIZE * 3 + 128 * CORE_OPT_VALUE_SIZE + Native.POINTER_SIZE;
    private static final int CORE_OPT_DEF_V2_SIZE =
            Native.POINTER_SIZE * 6 + 128 * CORE_OPT_VALUE_SIZE + Native.POINTER_SIZE;

    private void registerCoreOption(String key, String defaultValue) {
        if (key == null || key.isEmpty()) return;
        if (key.startsWith("ppsspp_")) defaultValue = applyPpssppDefault(key, defaultValue);
        if (key.startsWith("pcsx2_"))  defaultValue = applyPcsx2Default(key, defaultValue);
        defaultValue = applyFlycastDefault(key, defaultValue);
        coreOptions.put(key, defaultValue);
        LOGGER.info("  core opt: {} = {}", key, defaultValue);
    }

    private static String applyPcsx2Default(String key, String defaultValue) {
        String preset = VideoQualityPresets.pcsx2Default(key);
        return preset != null ? preset : defaultValue;
    }

    private static String applyPcsxRearmedDefault(String key, String defaultValue) {
        String preset = VideoQualityPresets.pcsxRearmedDefault(key);
        return preset != null ? preset : defaultValue;
    }

    private static String applyFlycastDefault(String key, String defaultValue) {
        String preset = VideoQualityPresets.flycastDefault(key);
        if (preset != null) return preset;
        return switch (key) {
            case "reicast_sh4clock" -> "200";
            case "reicast_auto_skip_frame", "reicast_frame_skipping",
                 "reicast_gdrom_fast_loading", "reicast_dc_32mb_mod",
                 "reicast_widescreen_cheats", "reicast_widescreen_hack" -> "disabled";
            case "reicast_oit_abuffer_size" -> "512MB";
            case "reicast_oit_layers" -> "32";
            default -> defaultValue;
        };
    }

    private static String applyPpssppDefault(String key, String defaultValue) {
        String preset = VideoQualityPresets.ppssppDefault(key);
        if (preset != null) return preset;
        return switch (key) {
            case "ppsspp_backend" -> "opengl";
            case "ppsspp_mulitsample_level" -> "Disabled";
            case "ppsspp_skip_buffer_effects", "ppsspp_skip_gpu_readbacks" -> "disabled";
            case "ppsspp_lazy_texture_caching" -> "enabled";
            case "ppsspp_lower_resolution_for_effects" -> "Balanced";
            case "ppsspp_gpu_hardware_transform" -> "enabled";
            case "ppsspp_inflight_frames" -> "Up to 1";
            default -> defaultValue;
        };
    }

    private void walkCoreOptionDefinitions(Pointer arrayBase, int structSize) {
        long offset = 0;
        while (true) {
            Pointer keyPtr = arrayBase.getPointer(offset);
            if (keyPtr == null) break;
            String key = keyPtr.getString(0);
            if (key == null || key.isEmpty()) break;
            Pointer defPtr = arrayBase.share(offset);
            Pointer defaultPtr = defPtr.getPointer(structSize - Native.POINTER_SIZE);
            String defaultValue = defaultPtr != null ? defaultPtr.getString(0) : "";
            registerCoreOption(key, defaultValue != null ? defaultValue : "");
            offset += structSize;
        }
    }

    private void handleSetCoreOptions(Pointer data) {
        walkCoreOptionDefinitions(data, CORE_OPT_DEF_V1_SIZE);
    }

    private void handleSetCoreOptionsV2(Pointer data) {
        Pointer definitions = data.getPointer(Native.POINTER_SIZE);
        if (definitions != null) walkCoreOptionDefinitions(definitions, CORE_OPT_DEF_V2_SIZE);
    }

    /** {@code retro_core_options_v2_intl} → parse {@code us->definitions}. */
    private void handleSetCoreOptionsV2Intl(Pointer data) {
        Pointer usPtr = data.getPointer(0);
        if (usPtr == null) return;
        Pointer definitions = usPtr.getPointer(Native.POINTER_SIZE);
        if (definitions != null) walkCoreOptionDefinitions(definitions, CORE_OPT_DEF_V2_SIZE);
    }

    private void handleSetVariables(Pointer data) {
        // retro_variable: { const char* key; const char* value; }, null key = end.
        // value = "desc; opt1|opt2|..." — default is the last option.
        long offset = 0;
        while (true) {
            Pointer keyPtr = data.getPointer(offset);
            if (keyPtr == null) break;
            String key = keyPtr.getString(0);
            offset += Native.POINTER_SIZE;

            Pointer valPtr = data.getPointer(offset);
            String value = valPtr != null ? valPtr.getString(0) : "";
            offset += Native.POINTER_SIZE;

            String defaultValue = "";
            if (value.contains(";")) {
                String opts = value.substring(value.indexOf(';') + 1).trim();
                String[] parts = opts.split("\\|");
                if (parts.length > 0) defaultValue = parts[parts.length - 1].trim();
            }
            registerCoreOption(key, defaultValue);
        }
    }

    private boolean handleGetVariable(Pointer data) {
        // Whole function in try: Flycast passes garbage pointers during retro_run
        try {
            if (data == null) return false;
            if (Pointer.nativeValue(data) == 0) return false;

            Pointer keyPtr = data.getPointer(0);
            if (keyPtr == null) return false;
            long keyAddr = Pointer.nativeValue(keyPtr);
            if (keyAddr == 0 || keyAddr < 0x10000) return false;

            String key = keyPtr.getString(0);
            if (key == null || key.isEmpty()) return false;

            String val = coreOptions.get(key);
            if ((val == null || val.isEmpty()) && key.startsWith("ppsspp_"))
                val = applyPpssppDefault(key, "");
            if (key.startsWith("pcsx2_"))
                val = applyPcsx2Default(key, val != null ? val : "");
            if (key.startsWith("pcsx_rearmed_"))
                val = applyPcsxRearmedDefault(key, val != null ? val : "");
            if (key.startsWith("flycast_") || key.startsWith("reicast_"))
                val = applyFlycastDefault(key, val != null ? val : "");
            if ((val == null || val.isEmpty()) && "pcsx2_bios".equals(key)) {
                val = findFirstPcsx2BiosFilename();
                if (val != null) LOGGER.info("PCSX2 bios auto-selected: {}", val);
            }
            if (val != null && !val.isEmpty()) {
                Memory valMem = new Memory(val.length() + 1);
                valMem.setString(0, val);
                allocatedVarMemory.add(valMem);
                data.setPointer(Native.POINTER_SIZE, valMem);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false; // any crash → core default
        }
    }

    // =====================================================================
    
    // =====================================================================

    /** Max ROM size to load into memory (cartridges; larger → path only). */
    private static final long MAX_IN_MEMORY_ROM = 64L * 1024 * 1024;

    private static boolean isPathOnlyRom(String lowerPath, Path romPath) {
        if (lowerPath.endsWith(".cue") || lowerPath.endsWith(".gdi")) return true;
        if (lowerPath.endsWith(".iso") || lowerPath.endsWith(".chd")
                || lowerPath.endsWith(".cso") || lowerPath.endsWith(".mdf")
                || lowerPath.endsWith(".nrg") || lowerPath.endsWith(".img")) {
            return true;
        }
        try {
            return Files.size(romPath) > MAX_IN_MEMORY_ROM;
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Load a game.
     *
     * @param romPath path to ROM file
     * @return true if the core accepted the game
     */
    public boolean loadGame(Path romPath) {
        loadedGamePath = romPath.toString();

        var info = new LibretroBridge.RetroGameInfo();
        info.path = romPath.toAbsolutePath().toString();

        // Do NOT read discs and large files into memory: cores (PCSX2/PPSSPP/Flycast)
        // open tracks from disk by path; multi-GB ISO does not fit in byte[].
        String lower = romPath.toString().toLowerCase();
        boolean pathOnly = isPathOnlyRom(lower, romPath);

        if (!pathOnly) {
            try {
                byte[] romBytes = Files.readAllBytes(romPath);
                info.data = new Memory(romBytes.length);
                info.data.write(0, romBytes, 0, romBytes.length);
                info.size = romBytes.length;
            } catch (IOException e) {
                LOGGER.error("Failed to read ROM: {}", romPath, e);
                info.data = null;
                info.size = 0;
            }
        } else {
            LOGGER.info("Disc/large image — passing path only: {}", romPath.getFileName());
            info.data = null;
            info.size = 0;
        }
        info.meta = "";

        if (isCitraCore()) {
            seedSharedCitraFiles();
        }

        boolean ok = core.retro_load_game(info);
        gameLoaded = ok;
        if (ok) {
            LOGGER.info("Game loaded: {}", romPath.getFileName());
            registerControllerPorts();

            var avInfo = new LibretroBridge.RetroSystemAVInfo();
            core.retro_get_system_av_info(avInfo);
            LOGGER.info("Resolution: {}x{} (max {}x{}), FPS: {}, Sample rate: {}",
                    avInfo.geometry.base_width, avInfo.geometry.base_height,
                    avInfo.geometry.max_width, avInfo.geometry.max_height,
                    avInfo.timing_fps, avInfo.timing_sample_rate);
            if (hwRenderActive) {
                int fboW = hwFboDim(avInfo, true);
                int fboH = hwFboDim(avInfo, false);
                if (fboW > 0 && fboH > 0) resizeHwFramebuffer(fboW, fboH);
            }
            timingFps = avInfo.timing_fps > 1.0 ? avInfo.timing_fps : 60.0;
            if (avInfo.timing_sample_rate > 8000.0) {
                audioSampleRate = avInfo.timing_sample_rate;
                audioPacing.setSampleRate((int) Math.round(audioSampleRate));
            }
            audioPacing.reset();

            // Release OUR instance from the load thread — core render thread will take it.
            if (hwRenderActive && glCtx != null) {
                try {
                    HeadlessGL.INSTANCE.hlg_release(glCtx);
                    LOGGER.info("EGL context released from init thread");
                } catch (Throwable t) {
                    LOGGER.warn("Failed to release EGL context: {}", t.getMessage());
                }
            }
            if (saveDir != null && usesFrontendBatteryRam()) {
                pendingBatteryRomPath = romPath;
                pendingBatteryLoad = true;
            }
        } else {
            LOGGER.error("Core rejected game: {}", romPath.getFileName());
        }
        return ok;
    }

    private void registerControllerPorts() {
        if (isFlycastCore()) {
            core.retro_set_controller_port_device(0, LibretroBridge.RETRO_DEVICE_JOYPAD);
            for (int port = 1; port < 4; port++)
                core.retro_set_controller_port_device(port, LibretroBridge.RETRO_DEVICE_NONE);
            LOGGER.info("Flycast: all controller ports registered (VMU slot A1 active)");
        } else {
            core.retro_set_controller_port_device(0, LibretroBridge.RETRO_DEVICE_JOYPAD);
            LOGGER.info("Controller registered: port 0 = JOYPAD");
        }
    }

    /** Run one frame (called from ThreadedEmulatorRuntime). */
    public void runFrame() {
        if (core == null) return;
        if (hwRenderActive && glCtx != null) {
            // Our GL instance must be current on the emulator thread
            try {
                if (HeadlessGL.INSTANCE.hlg_make_current(glCtx) != 0 && !hwGpuLoggedOnEmulatorThread) {
                    hwGpuLoggedOnEmulatorThread = true;
                    LOGGER.info("Emulator thread GPU: {}", HeadlessGL.INSTANCE.hlg_get_gpu_info(glCtx));
                }
            } catch (Throwable ignored) {}
            // PPSSPP/PCSX2: context_reset strictly on emulator thread (needs current GL)
            if ((isPpssppCore() || isPcsx2Core() || isCitraCore()) && !hwContextResetDone
                    && hwContextReset != null && Pointer.nativeValue(hwContextReset) != 0) {
                try {
                    LOGGER.info("context_reset on emulator thread @ {}", hwContextReset);
                    com.sun.jna.Function.getFunction(hwContextReset).invokeVoid(new Object[0]);
                    hwContextResetDone = true;
                } catch (Throwable t) {
                    LOGGER.warn("context_reset failed: {}", t.getMessage());
                }
            }
        }
        long t0 = System.nanoTime();
        core.retro_run();
        long dtMs = (System.nanoTime() - t0) / 1_000_000;
        if (dtMs > 100 && hwRenderActive && isPpssppCore())
            LOGGER.warn("retro_run took {}ms (possible ThreadFrame stall)", dtMs);
        if (dtMs > 2000 && hwRenderActive && isPcsx2Core())
            LOGGER.warn("PCSX2 retro_run took {}ms (slow — loading or shader compile?)", dtMs);
        maybeLoadPendingBattery();
    }

    private void maybeLoadPendingBattery() {
        if (!pendingBatteryLoad || saveDir == null || pendingBatteryRomPath == null) return;
        pendingBatteryLoad = false;
        com.retroconsole.platform.BatterySaveManager.loadIntoCore(
                this, pendingBatteryRomPath, Path.of(saveDir));
    }

    /**
     * Non-blocking frame poll: copies the latest frame into dst.
     *
     * @return true if a new frame was available
     */
    public boolean pollFrame(int[] dst) {
        if (!newFrame) return false;
        synchronized (frameLock) {
            if (frameBuffer == null) return false;
            System.arraycopy(frameBuffer, 0, dst, 0, Math.min(frameBuffer.length, dst.length));
            newFrame = false;
            return true;
        }
    }

    public int getWidth()  { synchronized (frameLock) { return frameWidth; } }
    public int getHeight() { synchronized (frameLock) { return frameHeight; } }

    @Override public double getTimingFps() { return timingFps; }
    @Override public boolean prefersAvLockstep() { return isPcsx2Core(); }
    @Override public double getAudioSampleRate() { return audioSampleRate; }

    // =====================================================================
    
    // =====================================================================

    private void appendAudio(Pointer data, int samples) {
        if (audioBulkScratch.length < samples) audioBulkScratch = new short[samples];
        data.read(0, audioBulkScratch, 0, samples);
        for (int i = 0; i < samples; i++) {
            audioRing[audioWrite] = audioBulkScratch[i];
            audioWrite = (audioWrite + 1) % AUDIO_RING_CAP;
            if (audioCount < AUDIO_RING_CAP) audioCount++;
        }
    }

    @Override
    public int readAudio(short[] dst, int maxShorts) {
        synchronized (audioLock) {
            int n = Math.min(audioCount, Math.min(dst.length, Math.max(0, maxShorts)));
            if (n == 0) return 0;
            int readPos = (audioWrite - audioCount + AUDIO_RING_CAP) % AUDIO_RING_CAP;
            if (readPos + n <= AUDIO_RING_CAP) {
                System.arraycopy(audioRing, readPos, dst, 0, n);
            } else {
                int first = AUDIO_RING_CAP - readPos;
                System.arraycopy(audioRing, readPos, dst, 0, first);
                System.arraycopy(audioRing, 0, dst, first, n - first);
            }
            audioCount -= n;
            return n;
        }
    }

    @Override
    public int getAudioAvailable() {
        synchronized (audioLock) { return audioCount; }
    }

    // =====================================================================
    
    // =====================================================================

    /** Joypad button (0 = released, 1 = pressed). */
    public void setButton(int buttonId, boolean pressed) {
        if (buttonId >= 0 && buttonId < 21)
            joypadState.set(buttonId, pressed ? 1 : 0);
        // Dreamcast triggers: mirror L/R to L2/R2 (digital) + analog —
        // Flycast may request any variant for one physical trigger
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L) {
            joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2, pressed ? 1 : 0);
            triggerState.set(0, pressed ? 32767 : 0);
        }
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R) {
            joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2, pressed ? 1 : 0);
            triggerState.set(1, pressed ? 32767 : 0);
        }
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2) triggerState.set(0, pressed ? 32767 : 0);
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2) triggerState.set(1, pressed ? 32767 : 0);
    }

    @Override
    public void setPointer(short x, short y, boolean pressed) {
        this.pointerX = x;
        this.pointerY = y;
        this.pointerPressed = pressed;
        joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_CURSOR_TOUCH, pressed ? 1 : 0);
    }

    /**
     * Analog stick.
     *
     * @param stick 0 = left, 1 = right
     * @param axis  0 = X, 1 = Y
     * @param value -32768..32767
     */
    public void setAnalog(int stick, int axis, short value) {
        int idx = stick * 2 + axis;
        if (idx >= 0 && idx < 4) analogState.set(idx, value);
    }

    /** Console reset. */
    public void reset() {
        if (core != null) core.retro_reset();
    }

    // =====================================================================
    //  Save states / SRAM
    // =====================================================================

    @Override
    public long getSerializeSize() {
        if (core == null) return 0;
        try {
            return core.retro_serialize_size();
        } catch (Throwable t) {
            return 0;
        }
    }

    public byte[] serialize() {
        long size = core.retro_serialize_size();
        if (size <= 0) return null;
        var mem = new Memory(size);
        if (core.retro_serialize(mem, size)) return mem.getByteArray(0, (int) size);
        return null;
    }

    public boolean unserialize(byte[] data) {
        if (data == null) return false;
        var mem = new Memory(data.length);
        mem.write(0, data, 0, data.length);
        return core.retro_unserialize(mem, data.length);
    }

    public byte[] getSaveRam() {
        Pointer data = core.retro_get_memory_data(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        long size = core.retro_get_memory_size(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        if (data == null || size <= 0) return null;
        return data.getByteArray(0, (int) size);
    }

    public boolean setSaveRam(byte[] sram) {
        if (sram == null) return false;
        Pointer data = core.retro_get_memory_data(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        long size = core.retro_get_memory_size(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        if (data == null || size <= 0) return false;
        data.write(0, sram, 0, (int) Math.min(sram.length, size));
        return true;
    }

    private String findFirstPcsx2BiosFilename() {
        return Pcsx2BiosResolver.findFirstBiosFilenameFromSystemDir(
                systemDir != null ? Path.of(systemDir) : null);
    }

    private void logPcsx2BiosDirIfNeeded() {
        if (!isPcsx2Core() || systemDir == null) return;
        Path biosDir = Path.of(systemDir, "pcsx2", "bios");
        if (!Files.isDirectory(biosDir)) {
            LOGGER.warn("PCSX2 bios dir missing: {}", biosDir);
            return;
        }
        try (var stream = Files.list(biosDir)) {
            var names = stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString()).toList();
            LOGGER.info("PCSX2 bios dir {}: {}", biosDir, names);
        } catch (IOException e) {
            LOGGER.warn("Failed to list PCSX2 bios dir {}", biosDir, e);
        }
    }

    // =====================================================================
    
    // =====================================================================

    /**
     * Multi-instance .so contract: call hlg_free() only after core threads
     * fully stopped (close() runs after ThreadedEmulatorRuntime stops — satisfied).
     */
    @Override
    public void close() throws Exception {
        if (core != null) {
            if (gameLoaded) {
                try {
                    core.retro_unload_game();
                } catch (Throwable t) {
                    LOGGER.warn("retro_unload_game failed", t);
                }
                try {
                    core.retro_deinit();
                } catch (Throwable t) {
                    LOGGER.warn("retro_deinit failed", t);
                }
            } else {
                LOGGER.warn("Skipping retro_deinit — game never loaded (avoids PCSX2 teardown crash)");
            }
            core = null;
        }
        // Fully release OUR GL instance (singleton context used to leak)
        if (glCtx != null) {
            try {
                HeadlessGL.INSTANCE.hlg_free(glCtx);
            } catch (Throwable t) {
                LOGGER.warn("hlg_free failed: {}", t.getMessage());
            }
            glCtx = null;
        }
        headlessGlReady = false;
        headlessGlApi = -1;
        hwRenderActive = false;
        hwGpuLoggedOnEmulatorThread = false;
        hwBottomLeftOrigin = true;
        hwContextReset = null;
        hwContextResetDone = false;
        hwPbufW = 0;
        hwPbufH = 0;
    }
}