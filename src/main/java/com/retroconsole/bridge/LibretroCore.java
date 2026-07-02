package com.retroconsole.bridge;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.List;

/** JNA interface to .libheadless_gl.so — provides headless EGL/Mesa GL context. */
interface HeadlessGL extends Library {
    HeadlessGL INSTANCE = Native.load(
            Paths.get("config/retroconsole/cores/.libheadless_gl.so").toAbsolutePath().toString(),
            HeadlessGL.class);
    int hlg_init(int major, int minor);
    void hlg_destroy();
    Pointer hlg_get_proc_address(String sym);
    void hlg_read_pixels(int[] viewport, Pointer pixels);
    void hlg_dump_hw_render(Pointer data, int size);
    Pointer hlg_get_framebuffer_ptr();
    Pointer hlg_get_proc_address_ptr();
    int hlg_make_current();
    void hlg_release();
    void hlg_debug_fbo();
    void hlg_track_fbo(int fbo);
    int hlg_resize(int w, int h);
}

/** Callback: unsigned long hlg_get_framebuffer(void) */
interface GetFramebufferCb extends Callback { long invoke(); }

/** Callback: void* hlg_get_proc_address(const char* sym) */
interface GetProcAddressCb extends Callback { Pointer invoke(String sym); }

/** JNA Structure matching struct retro_hw_render_callback (libretro, x86_64). */
class RetroHwRenderCallback extends Structure {
    public int context_type;              // offset  0, enum
    // 4 bytes padding
    public Pointer context_reset;         // offset  8, function pointer
    public GetFramebufferCb get_current_framebuffer; // offset 16, callback
    public GetProcAddressCb get_proc_address;        // offset 24, callback
    public byte depth;                    // offset 32
    public byte stencil;                  // offset 33
    public byte bottom_left_origin;       // offset 34
    // 1 byte padding
    public int version_major;             // offset 36, unsigned
    public int version_minor;             // offset 40, unsigned
    public byte cache_context;            // offset 44
    // 3 bytes padding
    public Pointer context_destroy;       // offset 48, function pointer

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
 * High-level wrapper around a libretro core.
 * Handles callback registration, game loading, frame execution,
 * and exposes a clean int[] ARGB framebuffer.
 */
public class LibretroCore implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroCore");

    private LibretroBridge core;
    private LibretroBridge.RetroEnvironment envCallback;
    private LibretroBridge.RetroVideoRefresh videoCallback;
    private LibretroBridge.RetroAudioSample audioCallback;
    private LibretroBridge.RetroAudioSampleBatch audioBatchCallback;
    private LibretroBridge.RetroInputPoll inputPollCallback;
    private LibretroBridge.RetroInputState inputStateCallback;

    // Frame buffer — written by video callback, read by pollFrame()
    private final Object frameLock = new Object();
    private int[] frameBuffer;
    private int frameWidth;
    private int frameHeight;
    private volatile boolean newFrame = false;
    
    private int pixelFormat = LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888;

    // HW render state for Flycast
    private boolean hwRenderActive = false;
    // Block GET_VARIABLE after init — Flycast passes junk pointers during retro_run
    private volatile boolean initComplete = false;
    // Keep callbacks alive so JNA trampolines aren't GC'd
    private GetFramebufferCb hwGetFramebuffer;
    private GetProcAddressCb hwGetProcAddress;
    private static final Pointer RETRO_HW_FRAME_BUFFER_VALID = Pointer.createConstant(-1);

    // Audio-based frame pacing — Flycast uses audio consumption rate for timing
    private final AudioPacing audioPacing = new AudioPacing(44100);

    // Input state — written by server thread, read by emulator thread
    // AtomicIntegerArray for lock-free inter-thread visibility
    private final java.util.concurrent.atomic.AtomicIntegerArray joypadState =
            new java.util.concurrent.atomic.AtomicIntegerArray(16); // 16 buttons
    private final java.util.concurrent.atomic.AtomicIntegerArray analogState =
            new java.util.concurrent.atomic.AtomicIntegerArray(4); // LX, LY, RX, RY
    // Analog trigger state (L2, R2) — separate from joypad digital buttons
    private final java.util.concurrent.atomic.AtomicIntegerArray triggerState =
            new java.util.concurrent.atomic.AtomicIntegerArray(2); // L2, R2 (0 or 32767)

    // System paths
    private String systemDir;
    private String saveDir;

    // Loaded game path (for save file naming)
    private String loadedGamePath;

    /**
     * Load a libretro core from a native library file.
     *
     * @param corePath Path to .dll or .so file
     */
    public static LibretroCore load(Path corePath) {
        LibretroCore lc = new LibretroCore();
        LOGGER.info("Loading libretro core: {}", corePath);

        // JNA needs to find the library. We use its absolute path.
        String absPath = corePath.toAbsolutePath().toString();

        lc.core = Native.load(absPath, LibretroBridge.class);
        LOGGER.info("Core loaded. API version: {}", lc.core.retro_api_version());

        // Set up callbacks BEFORE retro_init()
        lc.setupCallbacks();

        // CRITICAL for Flycast: NOP-out the log callback CALL inside
        // LogManager::LogWithFullPath BEFORE retro_init runs.
        // Flycast logs "[BOOT]: retro_init" from inside retro_init itself, and
        // its log_cb pointer is junk (0x4) because JNA can't deliver a real
        // function pointer via the environment callback. Patching BSS log_cb
        // (the old approach) was wrong — the real pointer lives at this+0x250
        // and is dereferenced as `call rcx`. NOPping the call site removes the
        // dereference entirely.
        lc.disableFlycastLogCall();

        // Initialize headless GL context (EGL/Mesa) for Flycast HW rendering.
        // Must happen AFTER disableFlycastLogCall (needs .so loaded) and BEFORE retro_init.
        try {
            int glOk = HeadlessGL.INSTANCE.hlg_init(3, 1);
            LOGGER.info("Headless GL context: {}", glOk != 0 ? "OK" : "FAILED");
        } catch (Throwable t) {
            LOGGER.warn("Headless GL init failed (Flycast DC games won't work): {}", t.getMessage());
        }

        lc.core.retro_init();
        LOGGER.info("Core initialized.");

        // Query system info
        var sysInfo = new LibretroBridge.RetroSystemInfo();
        lc.core.retro_get_system_info(sysInfo);
        LOGGER.info("Core: {} v{}", sysInfo.library_name, sysInfo.library_version);

        return lc;
    }

    private void setupCallbacks() {
        // Environment callback — handles core queries
        envCallback = (cmd, data) -> handleEnvironment(cmd, data);
        core.retro_set_environment(envCallback);

        // Video callback — receives frames from the core
        videoCallback = (data, width, height, pitch) -> {
            if (width <= 0 || height <= 0) {
                newFrame = true; // HW render: frame is in GL framebuffer
                return;
            }

            synchronized (frameLock) {
                if (frameBuffer == null || frameWidth != width || frameHeight != height) {
                    frameBuffer = new int[width * height];
                    frameWidth = width;
                    frameHeight = height;
                }

                int[] fb = frameBuffer;
                // HW rendering: data is RETRO_HW_FRAME_BUFFER_VALID or null.
                // Read pixels from GL framebuffer via headless_gl readback.
                if (hwRenderActive && (data == null || Pointer.nativeValue(data) == -1 || Pointer.nativeValue(data) == 0)) {
                    try {
                        // Resize PBuffer to match game resolution
                        HeadlessGL.INSTANCE.hlg_resize(width, height);
                        HeadlessGL.INSTANCE.hlg_debug_fbo();
                        int[] vp = new int[4];
                        int numPixels = width * height;
                        Memory nativePixels = new Memory((long) numPixels * 4);
                        HeadlessGL.INSTANCE.hlg_read_pixels(vp, nativePixels);
                        int readW = vp[2] > 0 ? vp[2] : width;
                        int readH = vp[3] > 0 ? vp[3] : height;
                        for (int y = 0; y < readH && y < height; y++) {
                            int srcY = readH - 1 - y;
                            for (int x = 0; x < readW && x < width; x++) {
                                long off = ((long) srcY * readW + x) * 4;
                                int r = nativePixels.getByte(off)     & 0xFF;
                                int g = nativePixels.getByte(off + 1) & 0xFF;
                                int b = nativePixels.getByte(off + 2) & 0xFF;
                                fb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                        }
                    } catch (Throwable t) {
                        // GL readback failed — fill with dark gray so we know rendering attempted
                        java.util.Arrays.fill(fb, 0xFF202020);
                    }
                    newFrame = true;
                    return;
                }

                switch (pixelFormat) {
                    case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888 -> {
                        int stride = (int) (pitch / 4);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int srcOffset = y * stride + x;
                                int pixel = data.getInt((long) srcOffset * 4);
                                fb[y * width + x] = 0xFF000000 | (pixel & 0x00FFFFFF);
                            }
                        }
                    }
                    case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565 -> {
                        // Input is RGB565 (2 bytes per pixel)
                        int stride = (int) (pitch / 2);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                short pixel = data.getShort((long) (y * stride + x) * 2);
                                int r = ((pixel >> 11) & 0x1F) << 3;
                                int g = ((pixel >> 5) & 0x3F) << 2;
                                int b = (pixel & 0x1F) << 3;
                                fb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                        }
                    }
                    case LibretroBridge.RETRO_PIXEL_FORMAT_0RGB1555 -> {
                        // Input is 0RGB1555 (2 bytes per pixel)
                        int stride = (int) (pitch / 2);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                short pixel = data.getShort((long) (y * stride + x) * 2);
                                int r = ((pixel >> 10) & 0x1F) << 3;
                                int g = ((pixel >> 5) & 0x1F) << 3;
                                int b = (pixel & 0x1F) << 3;
                                fb[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                        }
                    }
                }
                newFrame = true;
            }
        };
        core.retro_set_video_refresh(videoCallback);

        // Audio callbacks — use audio production rate for frame pacing.
        // Flycast relies on audio consumption as its timing signal.
        audioCallback = (left, right) -> { /* discard */ };
        core.retro_set_audio_sample(audioCallback);

        audioBatchCallback = (data, frames) -> {
            audioPacing.consumeSamples((int) frames);
            return frames;
        };
        core.retro_set_audio_sample_batch(audioBatchCallback);

        // Input poll callback
        inputPollCallback = () -> { /* nothing to poll, state is already set */ };
        core.retro_set_input_poll(inputPollCallback);

        // Input state callback — returns button/analog state
        inputStateCallback = (port, device, index, id) -> {
            if (port != 0) return 0;

            // Joypad buttons: device=1 (JOYPAD), index=0, id=0..15
            if (device == LibretroBridge.RETRO_DEVICE_JOYPAD) {
                if (index == 0 && id >= 0 && id < 16) {
                    return (short) joypadState.get(id);
                }
            }
            // Analog sticks & triggers: device=5 (ANALOG)
            if (device == LibretroBridge.RETRO_DEVICE_ANALOG) {
                // Left stick
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_LEFT) {
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_X) return (short) analogState.get(0);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_Y) return (short) analogState.get(1);
                }
                // Right stick
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_X) return (short) analogState.get(2);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_Y) return (short) analogState.get(3);
                }
                // Analog triggers (L/R or L2/R2) — index=2
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
                    // Flycast may use L(10)/R(11) or L2(12)/R2(13) for Dreamcast triggers
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

    // Flycast patches — Dreamcast core's libretro callbacks are broken under JNA
    // (function pointers come through as 0x4 garbage). We work around this by
    // NOPping the call sites in flycast_libretro.so that dereference Flycast's
    // internal log_cb slot (BSS at 0x26d82e0) and its LogManager::LogWithFullPath
    // call site. This makes Flycast skip its printf-style logging entirely, which
    // is fine for our purposes (we don't display Flycast's log output anywhere).
    //
    // All offsets below are valid for the build of flycast_libretro.so in this
    // workspace (md5 209786943fee54e07b4c0849d84907ad). If Flycast is updated,
    // these may shift — see asm in /tmp/flycast.S to re-derive.
    private static final int FLYCAST_LOG_CALL_OFFSET = 0x39abad;  // `call rcx` in LogManager::LogWithFullPath (2 bytes)
    private static final int FLYCAST_BSS_CALL_1     = 0x397b08;  // `call [0x26d82e0]` in retro_load_game (6 bytes)
    private static final int FLYCAST_BSS_CALL_2     = 0x3985c7;  // `call [0x26d82e0]` in retro_load_game (6 bytes)
    private static final int FLYCAST_BSS_CALL_3     = 0x399f28;  // `call [0x26d82e0]` in retro_init/other (6 bytes)
    private static final byte[] NOP2 = new byte[] { (byte) 0x90, (byte) 0x90 };
    private static final byte[] NOP6 = new byte[] { (byte) 0x90, (byte) 0x90, (byte) 0x90,
                                                    (byte) 0x90, (byte) 0x90, (byte) 0x90 };

    // C library symbols we call via JNA to make a page writable before patching.
    private interface LibC extends com.sun.jna.Library {
        LibC INSTANCE = com.sun.jna.Native.load("c", LibC.class);
        int mprotect(Pointer addr, long len, int prot);
        int PROT_READ  = 0x1;
        int PROT_WRITE = 0x2;
        int PROT_EXEC  = 0x4;
    }

    /**
     * Patch Flycast's broken log callback machinery so it never tries to invoke
     * a function pointer that came in through JNA (which delivers 0x4 garbage
     * for C function pointers, causing SIGSEGV). We NOP the call sites in code
     * rather than fixing the BSS slot — this way Flycast skips its internal
     * logging entirely without us having to provide a valid C function.
     *
     * Safe no-op for cores that are not flycast_libretro.so.
     *
     * Must be called BEFORE retro_init().
     */
    private void disableFlycastLogCall() {
        long baseAddr;
        try {
            baseAddr = 0;
            String mapsPath = "/proc/" + ProcessHandle.current().pid() + "/maps";
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(mapsPath))) {
                if (line.contains("flycast_libretro.so") && line.contains("r-xp")) {
                    String addr = line.split("-")[0];
                    baseAddr = Long.parseLong(addr, 16);
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

        long pageSize = 4096;
        nopFlycastCallSite(baseAddr, FLYCAST_LOG_CALL_OFFSET, NOP2);
        nopFlycastCallSite(baseAddr, FLYCAST_BSS_CALL_1,     NOP6);
        nopFlycastCallSite(baseAddr, FLYCAST_BSS_CALL_2,     NOP6);
        nopFlycastCallSite(baseAddr, FLYCAST_BSS_CALL_3,     NOP6);
    }

    /** NOP a range of bytes inside flycast_libretro.so (handles mprotect). */
    private void nopFlycastCallSite(long baseAddr, int offset, byte[] nopBytes) {
        long pageSize = 4096;
        long targetAddr = baseAddr + offset;
        long pageStart = targetAddr & ~(pageSize - 1);
        long pageEnd   = (targetAddr + nopBytes.length + pageSize - 1) & ~(pageSize - 1);
        long pageLen   = pageEnd - pageStart;
        int rc = LibC.INSTANCE.mprotect(new Pointer(pageStart), pageLen,
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
                Long.toHexString(offset), nopBytes.length,
                bytesToHex(original), ok);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x & 0xFF));
        return sb.toString().trim();
    }

    private boolean handleEnvironment(int cmd, Pointer data) {
        switch (cmd) {
            case 1 -> { // RETRO_ENVIRONMENT_GET_CAN_DUPE
                if (data != null) data.setByte(0, (byte) 1);
                return true;
            }
            case 9 -> { // RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY
                if (systemDir != null) {
                    data.setPointer(0, new Memory(systemDir.length() + 1));
                    data.getPointer(0).setString(0, systemDir);
                    LOGGER.info("Core requested GET_SYSTEM_DIRECTORY -> {}", systemDir);
                    return true;
                }
                LOGGER.warn("Core requested GET_SYSTEM_DIRECTORY but systemDir is null");
                return false;
            }
            case 10 -> { // RETRO_ENVIRONMENT_SET_PIXEL_FORMAT
                int fmt = data.getInt(0);
                pixelFormat = fmt;
                LOGGER.info("Core set pixel format: {}", switch (fmt) {
                    case LibretroBridge.RETRO_PIXEL_FORMAT_0RGB1555 -> "0RGB1555";
                    case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888 -> "XRGB8888";
                    case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565 -> "RGB565";
                    default -> "UNKNOWN(" + fmt + ")";
                });
                return true;
            }
            case 11 -> { // RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY
                if (saveDir != null) {
                    data.setPointer(0, new Memory(saveDir.length() + 1));
                    data.getPointer(0).setString(0, saveDir);
                    return true;
                }
                return false;
            }
            case 12 -> { // RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS
                return true; // acknowledge, we don't support but don't crash
            }
            case 13 -> { // RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS
                return true;
            }
            case 14 -> { // RETRO_ENVIRONMENT_SET_HW_RENDER
                if (data == null) return false;
                try {
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
                    LOGGER.info("Flycast requests HW render: context_type={} ({})", ctxType, ctxName);

                    // Only accept OpenGL/OpenGL Core contexts (1, 3, 4).
                    // Reject Vulkan (6) — we only have Mesa software OpenGL.
                    if (ctxType != 1 && ctxType != 3 && ctxType != 4) {
                        LOGGER.info("Rejecting {} — only OpenGL supported", ctxName);
                        return false;
                    }

                    // Dump the raw struct to find actual field offsets
                    HeadlessGL.INSTANCE.hlg_dump_hw_render(data, 80);

                    // Get raw C function pointers from .libheadless_gl.so
                    // These are actual machine code addresses Flycast can call directly.
                    Pointer fbPtr = HeadlessGL.INSTANCE.hlg_get_framebuffer_ptr();
                    Pointer procPtr = HeadlessGL.INSTANCE.hlg_get_proc_address_ptr();
                    LOGGER.info("  get_framebuffer @ {}, get_proc_address @ {}", fbPtr, procPtr);
                    data.setPointer(16, fbPtr);   // get_current_framebuffer
                    data.setPointer(24, procPtr);  // get_proc_address
                    data.setByte(44, (byte) 1);    // cache_context = true

                    // Verify writes persisted
                    LOGGER.info("  VERIFIED: fb@16={}, proc@24={}, cache@44={}",
                            data.getPointer(16), data.getPointer(24), data.getByte(44));

                    // CRITICAL: Flycast's GL function table (__rglgen_*) is populated by
                    // context_reset callback, NOT by get_proc_address directly.
                    // The frontend MUST call context_reset() after providing the hw_render
                    // struct so Flycast can load GL functions via get_proc_address.
                    Pointer contextResetPtr = data.getPointer(8);
                    if (contextResetPtr != null && Pointer.nativeValue(contextResetPtr) != 0) {
                        LOGGER.info("  Calling context_reset @ {} to init GL function table", contextResetPtr);
                        // context_reset is: void (*context_reset)(void)
                        // Call it as a C function pointer via JNA
                        var contextReset = com.sun.jna.Function.getFunction(contextResetPtr);
                        contextReset.invokeVoid(new Object[0]);
                        LOGGER.info("  context_reset completed");
                    } else {
                        LOGGER.warn("  context_reset is NULL!");
                    }

                    hwRenderActive = true;
                    LOGGER.info("HW render context provided for {} (headless EGL/Mesa software)", ctxName);
                    return true;
                } catch (Throwable t) {
                    LOGGER.error("Failed to handle SET_HW_RENDER", t);
                    return false;
                }
            }
            case 15 -> { // RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS
                // Nestopia dereferences data[0] as a device-name string
                // ("nes", "famicom", ...) and calls strcmp() on it. If we
                // return true without filling data, that pointer is NULL
                // and strcmp segfaults. Returning false makes the core
                // skip the descriptor check entirely. Other cores are fine
                // with this.
                return false;
            }
            case 16 -> { // RETRO_ENVIRONMENT_SET_VARIABLES
                LOGGER.info("Core requesting SET_VARIABLES");
                if (data != null) handleSetVariables(data);
                return true;
            }
            case 17 -> { // RETRO_ENVIRONMENT_GET_VARIABLE
                if (!initComplete && data != null && handleGetVariable(data)) return true;
                return false;
            }
            case 23 -> { // Unknown — Flycast-specific (NOT in standard libretro.h)
                // Flycast's retro_load_game calls env_cb(23, &buf) and, if we
                // return true, then dereferences another BSS pointer (0x26d82e0)
                // as a function. JNA delivers that callback pointer as garbage
                // (value 0x4), causing SIGSEGV. Returning false makes Flycast
                // skip the call entirely.
                LOGGER.debug("Ignoring Flycast env cmd=23");
                return false;
            }
            case 27 -> { // RETRO_ENVIRONMENT_SET_MESSAGE
                // Just acknowledge and ignore. Returning true here with a
                // log callback pointer worked for Flycast via the BSS slot
                // at 0x26d82e0, but caused crashes in Nestopia / other cores
                // that interpret data->msg as a displayable string. Flycast
                // specifically is patched in disableFlycastLogCall() to NOP
                // the call sites that dereference its BSS slot, so we don't
                // need to provide a real callback for it either.
                return false;
            }
            case 28 -> { // RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO
                LOGGER.info("Core requesting SET_SYSTEM_AV_INFO");
                // Re-read the AV info structure if needed
                return true;
            }
            case 31 -> { // RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK
                LOGGER.info("Core requesting SET_AUDIO_CALLBACK — accepting");
                // Flycast wants to use pull-based audio. We accept but don't actually produce audio.
                // The data points to struct retro_audio_callback { retro_audio_sample_batch_t sample_batch; retro_audio_state_t state; ... }
                // We just return true to acknowledge. Flycast won't crash on null audio callback.
                return true;
            }
            case 35 -> { // RETRO_ENVIRONMENT_SET_CONTROLLER_INFO
                return true; // accept controller info
            }
            case 37 -> { // RETRO_ENVIRONMENT_SET_GEOMETRY
                if (data != null) {
                    int baseW = data.getInt(0);
                    int baseH = data.getInt(4);
                    LOGGER.info("Core set geometry: {}x{}", baseW, baseH);
                }
                return true;
            }
            case 52 -> { // RETRO_ENVIRONMENT_GET_INPUT_BITMASKS
                LOGGER.info("Core requesting GET_INPUT_BITMASKS");
                return true; // we support input bitmasks
            }
            case 55 -> { // RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO
                return true; // accept but ignore
            }
            case 57 -> { // RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY
                return true;
            }
            case 69 -> { // RETRO_ENVIRONMENT_SET_FASTFORWARDING_OVERRIDE
                return true;
            }
            case 18 -> { // RETRO_ENVIRONMENT_GET_LOG_INTERFACE
                // Flycast asks for a log callback here but ignores the pointer
                // we return — it uses its own internal log_cb (which JNA cannot
                // deliver correctly, hence the 0x4 garbage). The actual fix is
                // disablingFlycastLogCall() which NOPs the call site in code.
                // Return false so Flycast falls back to its (broken) default;
                // the patched code path ensures we never actually invoke it.
                return false;
            }
            case 65581 -> { // Extended environment (high bit = 0x10000 + cmd)
                LOGGER.debug("Extended env cmd=65581");
                return false;
            }
            default -> {
                LOGGER.debug("Unhandled env callback cmd={}", cmd);
                return false;
            }
        }
    }

    /** Stores core-declared variable keys so we can respond to GET_VARIABLE. */
    private final java.util.Map<String, String> coreOptions = new java.util.LinkedHashMap<>();
    /** Keep allocated Memory alive so GC doesn't free strings before core reads them. */
    private final java.util.List<Memory> allocatedVarMemory = new java.util.ArrayList<>();

    private void handleSetVariables(Pointer data) {
        // Each retro_variable is { const char *key; const char *value; }
        // value contains "desc; option1|option2|..."  — key\0value\0 ... null terminator
        long offset = 0;
        while (true) {
            Pointer keyPtr = data.getPointer(offset);
            if (keyPtr == null) break; // null key terminates the array
            String key = keyPtr.getString(0);
            offset += Native.POINTER_SIZE;

            Pointer valPtr = data.getPointer(offset);
            String value = valPtr != null ? valPtr.getString(0) : "";
            offset += Native.POINTER_SIZE;

            // Parse default value from "description; option1|option2|default|..."
            String defaultValue = "";
            if (value.contains(";")) {
                String opts = value.substring(value.indexOf(';') + 1).trim();
                String[] parts = opts.split("\\|");
                if (parts.length > 0) {
                    defaultValue = parts[parts.length - 1].trim();
                }
            }

            // Override rendering backend to software if this is the renderer option
            if (key.contains("rend") && defaultValue.toLowerCase().contains("opengl")) {
                // Try to force software rendering
                for (String part : value.split("\\|")) {
                    String p = part.trim().toLowerCase();
                    if (p.contains("soft") || p.contains("cpu")) {
                        defaultValue = part.trim();
                        LOGGER.info("Overriding {} to software renderer: {}", key, defaultValue);
                        break;
                    }
                }
            }

            // Flycast speed/accuracy fixes
            if (key.equals("reicast_sh4clock")) {
                defaultValue = "200"; // real Dreamcast speed, not 500 MHz OC
            }
            if (key.equals("reicast_auto_skip_frame")) {
                defaultValue = "disabled";
            }
            if (key.equals("reicast_frame_skipping")) {
                defaultValue = "disabled";
            }
            if (key.equals("reicast_gdrom_fast_loading")) {
                defaultValue = "disabled"; // accurate loading times
            }
            // Internal resolution — 12800x9600 default kills software renderer
            if (key.equals("reicast_internal_resolution")) {
                defaultValue = "640x480"; // native DC resolution
            }
            // 32MB RAM mod changes game behavior
            if (key.equals("reicast_dc_32mb_mod")) {
                defaultValue = "disabled"; // stock 16MB
            }
            // Texture upscaling is expensive on software renderer
            if (key.equals("reicast_texupscale")) {
                defaultValue = "1"; // no upscaling
            }
            // Widescreen cheats/hacks modify game code, can break timing
            if (key.equals("reicast_widescreen_cheats")) {
                defaultValue = "disabled";
            }
            if (key.equals("reicast_widescreen_hack")) {
                defaultValue = "disabled";
            }
            // OIT (order-independent transparency) is expensive
            if (key.equals("reicast_oit_abuffer_size")) {
                defaultValue = "512MB";
            }
            if (key.equals("reicast_oit_layers")) {
                defaultValue = "32";
            }

            coreOptions.put(key, defaultValue);
            LOGGER.info("  core var: {} = {} (default: {})", key, value, defaultValue);
        }
    }

    private boolean handleGetVariable(Pointer data) {
        // Wrap ENTIRE function — Flycast passes junk pointers during retro_run
        try {
            // retro_variable struct: { const char *key; const char *value; }
            if (data == null) return false;
            long dataAddr = Pointer.nativeValue(data);
            if (dataAddr == 0) return false;

            Pointer keyPtr = data.getPointer(0);
            if (keyPtr == null) return false;
            long keyAddr = Pointer.nativeValue(keyPtr);
            if (keyAddr == 0 || keyAddr < 0x10000) return false;

            String key = keyPtr.getString(0);
            if (key == null || key.isEmpty()) return false;

            String val = coreOptions.get(key);
            if (val != null) {
                Memory valMem = new Memory(val.length() + 1);
                valMem.setString(0, val);
                allocatedVarMemory.add(valMem);
                data.setPointer(Native.POINTER_SIZE, valMem);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false; // ANY crash → use default
        }
    }

    /**
     * Set directories that cores may query via environment callback.
     */
    public void setDirectories(String systemDir, String saveDir) {
        this.systemDir = systemDir;
        this.saveDir = saveDir;
    }

    /**
     * Load a game ROM.
     *
     * @param romPath Path to ROM file
     * @return true if game loaded successfully
     */
    public boolean loadGame(Path romPath) {
        loadedGamePath = romPath.toString();

        var info = new LibretroBridge.RetroGameInfo();
        info.path = romPath.toAbsolutePath().toString();

        // Multi-file disc images (CUE, GDI) must NOT be loaded into memory —
        // the core reads the descriptor from disk and opens referenced BIN/ISO
        // tracks via relative paths. Passing the descriptor as data crashes
        // cores like Flycast because they can't resolve track files from memory.
        String lower = romPath.toString().toLowerCase();
        boolean isDescriptor = lower.endsWith(".cue") || lower.endsWith(".gdi");

        if (!isDescriptor) {
            // Single-file ROMs — load into memory so cores that don't do
            // disk I/O still get the data.
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
            LOGGER.info("Multi-file disc image detected, passing path only: {}", romPath.getFileName());
            info.data = null;
            info.size = 0;
        }
        info.meta = "";

        boolean ok = core.retro_load_game(info);
        if (ok) {
            LOGGER.info("Game loaded: {}", romPath.getFileName());

            // Register controller — Flycast requires this to read input
            core.retro_set_controller_port_device(0, LibretroBridge.RETRO_DEVICE_JOYPAD);
            LOGGER.info("Controller registered: port 0 = JOYPAD");

            // Query AV info to know initial resolution
            var avInfo = new LibretroBridge.RetroSystemAVInfo();
            core.retro_get_system_av_info(avInfo);
            LOGGER.info("Resolution: {}x{}, FPS: {}, Sample rate: {}",
                    avInfo.geometry.base_width, avInfo.geometry.base_height,
                    avInfo.timing_fps, avInfo.timing_sample_rate);

            // Release EGL context from init thread so the emulator thread
            // can make it current via hlg_make_current().
            // MUST happen after context_reset + retro_load_game + av_info.
            if (hwRenderActive) {
                try {
                    HeadlessGL.INSTANCE.hlg_release();
                    LOGGER.info("EGL context released from init thread");
                } catch (Throwable t) {
                    LOGGER.warn("Failed to release EGL context: {}", t.getMessage());
                }
            }
        } else {
            LOGGER.error("Core rejected game: {}", romPath.getFileName());
        }

        initComplete = true; // block GET_VARIABLE from retro_run
        return ok;
    }

    /**
     * Execute one frame. Called from ThreadedEmulatorRuntime.
     */
    public void runFrame() {
        if (core != null) {
            // Ensure EGL context is current on this thread (emulator thread != server thread)
            if (hwRenderActive) {
                try { HeadlessGL.INSTANCE.hlg_make_current(); } catch (Throwable ignored) {}
            }
            core.retro_run();
        }
    }

    /**
     * Non-blocking frame poll. Copies latest frame into dst buffer.
     *
     * @param dst Destination buffer (int[width * height])
     * @return true if a new frame was available
     */
    public boolean pollFrame(int[] dst) {
        if (!newFrame) return false;

        synchronized (frameLock) {
            if (frameBuffer == null) return false;
            int copyLen = Math.min(frameBuffer.length, dst.length);
            System.arraycopy(frameBuffer, 0, dst, 0, copyLen);
            newFrame = false;
            return true;
        }
    }

    public int getWidth() {
        synchronized (frameLock) { return frameWidth; }
    }

    public int getHeight() {
        synchronized (frameLock) { return frameHeight; }
    }

    /**
     * Set joypad button state (0 = released, 1 = pressed).
     */
    public void setButton(int buttonId, boolean pressed) {
        if (buttonId >= 0 && buttonId < 16) {
            joypadState.set(buttonId, pressed ? 1 : 0);
        }
        // Dreamcast triggers: L/R → also set L2/R2 digital + analog trigger
        // Flycast may query either L(10) or L2(12) for the same physical trigger
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L) {
            joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2, pressed ? 1 : 0);
            triggerState.set(0, pressed ? 32767 : 0);
        }
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R) {
            joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2, pressed ? 1 : 0);
            triggerState.set(1, pressed ? 32767 : 0);
        }
        // Also handle direct L2/R2 presses (future gamepads)
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2) {
            triggerState.set(0, pressed ? 32767 : 0);
        }
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2) {
            triggerState.set(1, pressed ? 32767 : 0);
        }
    }

    /**
     * Set analog stick state.
     *
     * @param stick 0=left, 1=right
     * @param axis  0=X, 1=Y
     * @param value -32768 to 32767
     */
    public void setAnalog(int stick, int axis, short value) {
        int idx = stick * 2 + axis;
        if (idx >= 0 && idx < 4) {
            analogState.set(idx, value);
        }
    }

    /**
     * Reset the console.
     */
    public void reset() {
        if (core != null) {
            core.retro_reset();
        }
    }

    // --- Save states ---

    public byte[] serialize() {
        long size = core.retro_serialize_size();
        if (size <= 0) return null;
        var mem = new Memory(size);
        if (core.retro_serialize(mem, size)) {
            return mem.getByteArray(0, (int) size);
        }
        return null;
    }

    public boolean unserialize(byte[] data) {
        if (data == null) return false;
        var mem = new Memory(data.length);
        mem.write(0, data, 0, data.length);
        return core.retro_unserialize(mem, data.length);
    }

    // --- SRAM (battery saves) ---

    public byte[] getSaveRam() {
        Pointer data = core.retro_get_memory_data(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        long size = core.retro_get_memory_size(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        if (data == null || size <= 0) return null;
        return data.getByteArray(0, (int) size);
    }

    public void setSaveRam(byte[] sram) {
        if (sram == null) return;
        Pointer data = core.retro_get_memory_data(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        long size = core.retro_get_memory_size(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        if (data != null && size > 0) {
            data.write(0, sram, 0, (int) Math.min(sram.length, size));
        }
    }

    @Override
    public void close() {
        if (core != null) {
            try {
                core.retro_unload_game();
                core.retro_deinit();
            } catch (Exception e) {
                LOGGER.warn("Error closing libretro core", e);
            }
            core = null;
        }
        hwRenderActive = false;
        hwGetFramebuffer = null;
        hwGetProcAddress = null;
    }
}
