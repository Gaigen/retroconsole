package com.retroconsole.bridge;

import com.retroconsole.config.RetroSettings;
import com.retroconsole.platform.Pcsx2BiosResolver;
import com.retroconsole.platform.VideoQualityPresets;
import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** JNA interface to .libheadless_gl.dll — headless WGL contexts on Windows (multi-instance). */
interface HeadlessGLWin extends com.sun.jna.Library {
    
    Pointer hlg_create();
    void    hlg_free(Pointer h);

    // Context functions (first parameter is instance handle)
    int     hlg_init_ex(Pointer h, int api, int major, int minor, int flags);
    void    hlg_destroy(Pointer h);
    int     hlg_make_current(Pointer h);
    void    hlg_release(Pointer h);
    int     hlg_resize(Pointer h, int w, int hgt);
    void    hlg_read_pixels(Pointer h, int[] viewport, Pointer pixels, int maxPixels, int reqW, int reqH);
    /** 21-int capture snapshot (canvas / renderbuffer / attachment / GL state). */
    void    hlg_capture_info(Pointer h, int[] out);
    void    hlg_debug_clear(Pointer h, int rgb);
    void    hlg_debug_fbo(Pointer h);
    /** Gate the periodic GL capture diagnostics ({@code debug.verboseLogs}). */
    void    hlg_set_verbose(int on);
    String  hlg_get_gpu_info(Pointer h);

    // Process-wide (no handle)
    void    hlg_dump_hw_render(Pointer data, int size);
    /** Switch the shared CRT to binary open() mode — POSIX ports truncated by TEXT mode. */
    void    hlg_set_crt_binary();
    long    hlg_get_framebuffer();
    Pointer hlg_get_proc_address(String sym);
    Pointer hlg_get_framebuffer_ptr();
    Pointer hlg_get_proc_address_ptr();
    Pointer hlg_get_log_cb_ptr();
    /** retro_vfs_interface (v3) — POSIX-port cores (mkxp-z) enumerate directories through it. */
    Pointer hlg_get_vfs_interface();
    /** Core-runtime exit latched for the calling thread (0 = still running). */
    int     hlg_core_exit_code();
    String  hlg_core_exit_message();
    void    hlg_core_exit_clear();
    /** Capture HotSpot UEF before Flycast core loads (diagnostic, optional). */
    void    hlg_capture_jvm_filter();
    /** Hook RtlAddVectoredExceptionHandler so Flycast VEH goes to tail. */
    void    hlg_hook_addveh();
    /** Reset isolated VEH state between core sessions (dispatcher + fly[]). */
    void    hlg_reset_veh_session();
}

/** winmm timer resolution — see {@code LibretroCoreWindows.raiseTimerResolution()}. */
interface WinmmTimer extends com.sun.jna.Library {
    int timeBeginPeriod(int ms);
    int timeEndPeriod(int ms);
}

/**
 * Windows implementation of {@link LibretroCore}.
 *
 * <p>THREADING: HW-render libretro cores require ALL core and GL-context work
 * on a SINGLE thread (retro_init, retro_load_game, context_reset, retro_run,
 * retro_unload_game, retro_deinit, context_destroy). The entire core lifecycle
 * runs on one per-instance retro-core thread.
 *
 * <p>GL: the native DLL is multi-instance — hlg_create()/hlg_free() create
 * independent instances (own hidden window + HGLRC + offscreen FBO each).
 * Multiple HW-render consoles can run simultaneously, each on its own
 * retro-core thread. libretro get_current_framebuffer/get_proc_address have
 * no user-data, so the DLL dispatches them via thread-local:
 * hlg_make_current(handle) binds the instance to the current thread.
 */
public class LibretroCoreWindows extends LibretroCore {

    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroCoreWindows");

    /**
     * The user's {@code debug.verboseLogs} switch. Everything periodic in this
     * class — audio-pump stats, frame capture diagnostics — is gated on it, so a
     * launcher running slf4j at DEBUG still gets a quiet log by default.
     */
    private static boolean verboseLogs() {
        return RetroSettings.verboseLogs();
    }

    /** libretro hw_render: uintptr_t get_current_framebuffer(void) */
    public interface HwGetCurrentFramebuffer extends Callback { long invoke(); }

    /** libretro hw_render: void* get_proc_address(const char* sym) */
    public interface HwGetProcAddress extends Callback { Pointer invoke(Pointer sym); }

    private static final java.util.List<LibretroBridge> KEEP_LOADED = new java.util.ArrayList<>();
    private static final java.util.List<Object> PINNED_CALLBACKS = new java.util.ArrayList<>();

    /**
     * Pin the headless GL JNA library forever: it patches ntdll, and if GC
     * collects NativeLibrary and Windows unloads the DLL, jmp hooks in ntdll
     * would point into unloaded memory and crash the entire process.
     */
    private static volatile HeadlessGLWin HEADLESS_GL;

    /**
     * Per-core option overrides by DLL name substring.
     * IMPORTANT: verify exact keys/values against the log
     * "Core queried option (no override): ..." — names vary between builds.
     */
    private static final java.util.Map<String, java.util.Map<String, String>> CORE_OVERRIDES =
            buildCoreOverrides();

    private static java.util.Map<String, java.util.Map<String, String>> buildCoreOverrides() {
        java.util.Map<String, java.util.Map<String, String>> all = new java.util.LinkedHashMap<>();
        all.put("flycast", VideoQualityPresets.flycastOverrides());
        all.put("pcsx_rearmed", java.util.Map.of(
                "pcsx_rearmed_memcard1", "libretro",
                "pcsx_rearmed_memcard2", "shared",
                "pcsx_rearmed_neon_enhancement_enable", "enabled",
                "pcsx_rearmed_gpu_unai_scale_hires", "enabled"));
        all.put("ppsspp", VideoQualityPresets.ppssppOverrides());
        all.put("pcsx2", VideoQualityPresets.pcsx2Overrides());
        all.put("melonds", VideoQualityPresets.melondsOverrides());
        all.put("desmume", VideoQualityPresets.desmumeOverrides());
        all.put("citra", VideoQualityPresets.citraOverrides());
        all.put("boom3", VideoQualityPresets.boom3Overrides());
        return java.util.Map.copyOf(all);
    }

    /** Diagnostic: log each unique queried option key once. */
    private final java.util.Set<String> loggedOptionKeys =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Identify by thread reference, not name: with multiple consoles the name
     * "retro-core-thread" would be the same for all, and instance A's task could
     * run inline on instance B's thread.
     */
    private volatile Thread coreThreadRef;

    private final java.util.concurrent.ExecutorService coreThread =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "retro-core-" + Integer.toHexString(System.identityHashCode(this)));
                t.setDaemon(true);
                coreThreadRef = t;
                return t;
            });

    /** Run a task on the core-owning thread and wait for the result. */
    private <T> T onCoreThread(java.util.concurrent.Callable<T> task) {
        if (Thread.currentThread() == coreThreadRef) {
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
            java.util.concurrent.Future<?> f = coreThread.submit(() -> {
                closeImpl();
                return null;
            });
            f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.warn("Core shutdown timed out after 10s — forcing GL teardown");
            // A core thread may still be INSIDE the module — no FreeLibrary, no
            // slot release. The slot stays poisoned until process restart; other
            // slots keep working.
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            LOGGER.warn("Core shutdown error: {}", c != null ? c.getMessage() : e);
        } finally {
            coreThread.shutdownNow();
            if (headlessGlReady) {
                try { headlessGl().hlg_destroy(); } catch (Throwable ignored) {}
                headlessGlReady = false;
            }
            if (hlgHandle != null) {
                try { rawHlg().hlg_free(hlgHandle); } catch (Throwable ignored) {}
                hlgHandle = null;
            }
        }
    }

    // setButton / setAnalog / pollFrame do not touch GL — safe to call from
    // the Minecraft game thread (thread-safe via atomics + frameLock).

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
    private int[] hwReadbackInts = new int[0];   // bulk scratch buffer (perf)
    private Pointer hwContextReset = null;
    private Pointer hwContextDestroy = null;
    private boolean hwContextResetDone = false;
    private boolean hwContextResetOk = false;
    private int hwGlMajor = 3;
    private int hwGlMinor = 3;
    // Actual frame dimensions read back (core viewport), not declared geometry.
    private volatile int hwActualW = 0;
    private volatile int hwActualH = 0;
    // GL version requested by the core in SET_HW_RENDER.
    private int hwReqGlMajor = 3;
    private int hwReqGlMinor = 3;
    // Degenerate video_refresh calls (mkxp-z reports 0x0 until the game's graphics
    // layer is up). Counted so a permanently black display has a log trail.
    private int zeroSizeFrames = 0;
    // Origin convention the core declared in retro_hw_render_callback (offset 34).
    // True = OpenGL bottom-left, so the readback must be flipped; false = the core
    // already delivers top-left rows. The Linux bridge has always honored this;
    // the Windows path used to overwrite the field with a hardcoded value.
    private boolean hwBottomLeftOrigin = true;

    // mkxp-z's retro build aliases GraphicsPrivate::winSize to scRes, which turns
    // every "window" call into a screen announcement:
    //
    //   Graphics.resize_screen(w, h)   reallocates the screen, sets
    //                                  glState.scissorBox to w x h, then announces.
    //   Graphics.resize_window(w, h)   only mutates winSize (== scRes), announces.
    //   Graphics.scale = f             == resize_window(scRes * f); this is what
    //                                  Pokémon Essentials uses for its Screen Size
    //                                  option (S/M/L/XL via pbSetResizeFactor).
    //
    // The second group announces a canvas the core never renders into: the retro
    // blit then reads past a screen buffer that kept the old size, and the surplus
    // comes out as a repeat-of-last-row-and-column smear — or, once the render
    // target has already grown, as a flat remainder with the picture stuck in a
    // corner.
    //
    // So an announcement is never applied on its own. retroBase* is the size the
    // core really owns; any different canvas is *probed*, and the core's own
    // scissor box is the proof of which path it took — only resize_screen() leaves
    // a box as large as the announcement. A probe that never sees that proof is a
    // scaled window and is ignored, which also keeps repeated Screen Size changes
    // from compounding (512 -> 768, then 768 x 1.5 = 1152, then 2304, …).
    private int retroBaseW = -1;
    private int retroBaseH = -1;
    /** Canvas currently being verified against the core's scissor box (0 = none). */
    private int retroProbeW;
    private int retroProbeH;
    private int retroProbeFrames;
    /** Canvas size the last "ignoring" line was logged for (log once per size). */
    private int retroRejectW = -1;
    private int retroRejectH = -1;
    /** A probe is settled this many processed frames after the announcement. */
    private static final int RETRO_PROBE_FRAMES = 6;
    /** Never probe a canvas more than this many times the base in either axis. */
    private static final int RETRO_GROWTH_LIMIT = 4;

    /**
     * Latched core-runtime exit. mkxp-z ends its sandbox from inside retro_run()
     * and never sends RETRO_ENVIRONMENT_SHUTDOWN on that path, so the last
     * (black) frame would otherwise stream forever with no explanation.
     */
    private volatile int coreExitCode;
    private volatile String coreExitMessage;
    /**
     * Last warning/error the core reported through SET_MESSAGE. Preferred over the
     * native latch text: mkxp-z's own log formatting drops the last character of
     * the line it copies, while this string arrives whole.
     */
    private volatile String lastCoreMessage;

    /** Instance handle in the multi-instance DLL (hlg_create/hlg_free). */
    private Pointer hlgHandle;
    private final Hlg hlgAdapter = new Hlg();

    /** Pinned JNA trampolines — must not be collected while core holds native pointers. */
    private final HwGetCurrentFramebuffer hwGetFb = () -> {
        if (!headlessGlReady) return 0L;
        return rawHlg().hlg_get_framebuffer();
    };
    private final HwGetProcAddress hwGetProc = sym -> {
        if (sym == null) return Pointer.NULL;
        String name = sym.getString(0);
        if (name == null || name.isEmpty()) return Pointer.NULL;
        Pointer p = rawHlg().hlg_get_proc_address(name);
        if (p == null || Pointer.nativeValue(p) == 0) {
            LOGGER.warn("get_proc_address -> NULL for symbol: {}", name);
            return Pointer.NULL;
        }
        return p;
    };

    private static HeadlessGLWin rawHlg() {
        HeadlessGLWin gl = HEADLESS_GL;
        if (gl != null) return gl;
        synchronized (LibretroCoreWindows.class) {
            if (HEADLESS_GL == null) {
                String path = Paths.get("config/retroconsole/cores/.libheadless_gl.dll")
                        .toAbsolutePath().toString();
                HEADLESS_GL = Native.load(path, HeadlessGLWin.class);
            }
            return HEADLESS_GL;
        }
    }

    private Pointer hlgHandle() {
        if (hlgHandle == null) {
            hlgHandle = rawHlg().hlg_create();
            if (hlgHandle == null || Pointer.nativeValue(hlgHandle) == 0) {
                hlgHandle = null;
                throw new IllegalStateException("hlg_create() failed");
            }
        }
        return hlgHandle;
    }

    /**
     * Adapter with legacy method names over the handle API — rest of the file
     * calls headlessGl().hlg_xxx(...) as before.
     */
    private Hlg headlessGl() {
        rawHlg(); // throws if DLL unavailable (for supportsHwRender)
        return hlgAdapter;
    }

    private final class Hlg {
        int hlg_init_ex(int api, int major, int minor, int flags) {
            return rawHlg().hlg_init_ex(hlgHandle(), api, major, minor, flags);
        }
        void hlg_destroy() {
            if (hlgHandle != null) rawHlg().hlg_destroy(hlgHandle);
        }
        int hlg_make_current() {
            return hlgHandle == null ? 0 : rawHlg().hlg_make_current(hlgHandle);
        }
        void hlg_release() {
            if (hlgHandle != null) rawHlg().hlg_release(hlgHandle);
        }
        int hlg_resize(int w, int h) {
            return rawHlg().hlg_resize(hlgHandle(), w, h);
        }
        void hlg_read_pixels(int[] viewport, Pointer pixels, int maxPixels, int reqW, int reqH) {
            rawHlg().hlg_read_pixels(hlgHandle(), viewport, pixels, maxPixels, reqW, reqH);
        }
        void hlg_capture_info(int[] out) {
            rawHlg().hlg_capture_info(hlgHandle(), out);
        }
        void hlg_debug_clear(int rgb) {
            rawHlg().hlg_debug_clear(hlgHandle(), rgb);
        }
        void hlg_debug_fbo() {
            if (hlgHandle != null) rawHlg().hlg_debug_fbo(hlgHandle);
        }
        void hlg_set_verbose(boolean on) {
            rawHlg().hlg_set_verbose(on ? 1 : 0);
        }
        String hlg_get_gpu_info() {
            return rawHlg().hlg_get_gpu_info(hlgHandle());
        }
        void hlg_dump_hw_render(Pointer data, int size) {
            rawHlg().hlg_dump_hw_render(data, size);
        }
        long hlg_get_framebuffer() { return rawHlg().hlg_get_framebuffer(); }
        Pointer hlg_get_proc_address(String sym) { return rawHlg().hlg_get_proc_address(sym); }
        Pointer hlg_get_framebuffer_ptr()  { return rawHlg().hlg_get_framebuffer_ptr(); }
        Pointer hlg_get_proc_address_ptr() { return rawHlg().hlg_get_proc_address_ptr(); }
        Pointer hlg_get_log_cb_ptr()       { return rawHlg().hlg_get_log_cb_ptr(); }
        Pointer hlg_get_vfs_interface()    { return rawHlg().hlg_get_vfs_interface(); }
        int hlg_core_exit_code()           { return rawHlg().hlg_core_exit_code(); }
        String hlg_core_exit_message()     { return rawHlg().hlg_core_exit_message(); }
        void hlg_core_exit_clear()         { rawHlg().hlg_core_exit_clear(); }
        void hlg_capture_jvm_filter() { rawHlg().hlg_capture_jvm_filter(); }
        void hlg_hook_addveh()        { rawHlg().hlg_hook_addveh(); }
        void hlg_reset_veh_session()  { rawHlg().hlg_reset_veh_session(); }
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

    /** Global VEH reset ONLY when the last Flycast session ends — while another
     *  session is running, resetting the dispatcher would kill its nvmem routing. */
    private void endVehSessionIfLast() {
        if (!vehSessionHeld) return;
        vehSessionHeld = false;
        if (FLYCAST_VEH_SESSIONS.decrementAndGet() == 0) {
            resetVehSession();
        }
    }

    /**
     * FreeLibrary the module so the next session on this slot starts from clean
     * globals. MUST run only after retro_unload_game + context_destroy +
     * retro_deinit + endVehSessionIfLast(), and after the proxy is removed from
     * KEEP_LOADED — any call through the old proxy after dispose() is a native crash.
     */
    private static void disposeCoreLibrary(LibretroBridge proxy) {
        if (proxy == null) return;
        try {
            com.sun.jna.Library.Handler handler = (com.sun.jna.Library.Handler)
                    java.lang.reflect.Proxy.getInvocationHandler(proxy);
            handler.getNativeLibrary().dispose(); // -> FreeLibrary + drop JNA cache
            LOGGER.info("Core module unloaded (FreeLibrary) — slot is clean for next session");
        } catch (Throwable t) {
            LOGGER.warn("Failed to unload core module: {}", t.getMessage());
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

    private boolean ensureHeadlessGl(int ctxType, int reqMajor, int reqMinor) {
        boolean gles = (ctxType == 2 || ctxType == 4);
        int api = gles ? 1 : 0;
        // Core profile only for OPENGL_CORE (3); for OPENGL (1) — compatibility.
        int flags = (ctxType == 3) ? 0 : 1;
        int major = reqMajor > 0 ? reqMajor : 3;
        int minor = reqMinor >= 0 ? reqMinor : 0;
        // For core profile, version must be >= 3.2; bump to 3.3.
        if (!gles && flags == 0 && (major < 3 || (major == 3 && minor < 3))) {
            major = 3;
            minor = 3;
        }
        if (gles) { major = (ctxType == 4) ? 3 : 2; minor = 0; }

        int profileKey = (api & 0xFF) | ((flags & 0xFF) << 8)
                | ((minor & 0xFF) << 16) | ((major & 0xFF) << 24);

        // IMPORTANT: even when profileKey matches, verify the context is actually
        // available to this thread — C side may have been recreated or taken over.
        if (headlessGlReady && headlessGlApi == profileKey
                && headlessGl().hlg_make_current() != 0) {
            return true;
        }
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
                headlessGl().hlg_set_verbose(verboseLogs());
            }
            String apiName = api == 1 ? "GLES" + major : "GL " + major + "." + minor
                    + (flags == 0 ? " Core" : " Compat");
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
        hwContextResetOk = false;
        hwContextReset = null;
        hwContextDestroy = null;
        hwGpuLoggedOnEmulatorThread = false;
        hwPbufW = 0;
        hwPbufH = 0;
        hwFramePending = false;
        hwFrameW = 0;
        hwFrameH = 0;
        hwBottomLeftOrigin = true;
        hwReadbackBuf = null;
        hwReadbackCap = 0;
        hwReadbackInts = new int[0];
        retroBaseW = retroBaseH = -1;
        retroProbeW = retroProbeH = 0;
        retroProbeFrames = 0;
        retroRejectW = retroRejectH = -1;
    }

    /** context_destroy while core is still initialized — must run before retro_deinit. */
    private void destroyHwGlContext() {
        if (!hwContextResetDone || hwContextDestroy == null
                || Pointer.nativeValue(hwContextDestroy) == 0) {
            return;
        }
        try {
            headlessGl().hlg_make_current();
            if (isPcsx2Core()) {
                // LRPS2 context_destroy may hang on MTGS — release GL and proceed to retro_deinit.
                LOGGER.info("PCSX2: skipping context_destroy (detach headless GL only)");
                try { headlessGl().hlg_release(); } catch (Throwable ignored) {}
            } else {
                LOGGER.info("Calling context_destroy @ {}", hwContextDestroy);
                com.sun.jna.Function.getFunction(hwContextDestroy).invokeVoid(new Object[0]);
                LOGGER.info("context_destroy completed");
            }
        } catch (Throwable t) {
            LOGGER.warn("context_destroy failed: {}", t.getMessage());
        } finally {
            hwContextResetDone = false;
            hwContextDestroy = null;
        }
    }

    private boolean coreInitialized;

    /** Cached retro_get_system_info().need_fullpath, read once after retro_init(). */
    private boolean needFullpath = false;

    /** On-disk DLL copy loaded by this instance (see CoreModulePool). */
    private CoreModulePool.Slot moduleSlot;

    /** This instance holds one Flycast VEH session (routing via headless_gl). */
    private boolean vehSessionHeld = false;

    /** Live Flycast VEH sessions in the process (future-proof for >1 slot). */
    private static final java.util.concurrent.atomic.AtomicInteger FLYCAST_VEH_SESSIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    private LibretroBridge core;
    private final String systemDir;
    private final String saveDir;

    public LibretroCoreWindows(Path corePath, String systemDir, String saveDir) {
        super(corePath);
        raiseTimerResolution();
        if (systemDir != null) {
            systemDir = Path.of(systemDir).toAbsolutePath().normalize().toString();
        }
        if (saveDir != null) {
            saveDir = Path.of(saveDir).toAbsolutePath().normalize().toString();
        }
        this.systemDir = systemDir;
        this.saveDir = saveDir;
        LOGGER.info("Windows libretro core stub created for {} (system={}, save={})",
                corePath, systemDir, saveDir);
    }

    private static volatile boolean timerResolutionRaised;

    /**
     * Windows rounds every short sleep up to the system timer quantum — 15.6 ms by
     * default. Measured here: Thread.sleep(16) took 17-32 ms and a 10 ms park woke at
     * 15.6 ms, so the frame loop stuttered and threaded audio arrived in ~16 ms lumps.
     * Request 1 ms resolution for the JVM's lifetime (what RetroArch/SDL do on the
     * same API). Harmless if the OS refuses.
     */
    private static void raiseTimerResolution() {
        if (timerResolutionRaised) return;
        synchronized (LibretroCoreWindows.class) {
            if (timerResolutionRaised) return;
            timerResolutionRaised = true;
            try {
                if (Native.load("winmm", WinmmTimer.class).timeBeginPeriod(1) == 0) {
                    LOGGER.info("System timer resolution raised to 1 ms");
                }
            } catch (Throwable t) {
                LOGGER.debug("timeBeginPeriod(1) unavailable: {}", t.getMessage());
            }
        }
    }

    private String coreName() {
        return corePath == null ? "" : corePath.getFileName().toString().toLowerCase();
    }

    private boolean isFlycastCore() {
        return coreName().contains("flycast");
    }

    private boolean isCitraCore() {
        return coreName().contains("citra");
    }

    private boolean isBoom3Core() {
        return coreName().contains("boom3");
    }

    private boolean isMkxpzCore() {
        return coreName().contains("mkxp");
    }

    /**
     * Cores whose process globals survive retro_deinit()/retro_unload_game() and
     * therefore MUST be unmapped between sessions.
     *
     * <p>boom3 (dhewm3): {@code common->Shutdown()} tears the engine down but the
     * globals it published ({@code cvarSystem} first of all) keep pointing at freed
     * memory. The next {@code retro_load_game()} in the same JVM reads core options
     * through {@code idCVar::Set*} — i.e. through that dangling pointer — and faults
     * with "Invalid memory access" right after {@code boom3_vr_turn_angle}. Only a
     * fresh LoadLibrary resets the module's .bss.
     *
     * <p>Flycast needs the same for its own globals (and its NOP-patched logging).
     */
    private boolean needsFreshModule() {
        return isFlycastCore() || isBoom3Core();
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
                } catch (java.io.IOException e) {
                    LOGGER.warn("Failed to seed shared Citra file {}: {}", rel, e.toString());
                }
            });
        } catch (java.io.IOException e) {
            LOGGER.warn("Failed to walk shared Citra dir: {}", e.toString());
        }
    }

    private boolean isPcsx2CoreLocal() {
        return coreName().contains("pcsx2");
    }

    /** Install AddVEH hook for Flycast isolation and/or PCSX2 fastmem wraps. */
    private boolean needsVehHook() {
        return isFlycastCore() || isPcsx2CoreLocal();
    }

    /** Flycast: handlers leave the Windows chain. PCSX2: wrap stays on chain. */
    private boolean needsVehIsolation() {
        return isFlycastCore();
    }

    /** Flycast/PPSSPP sync FPS via audio pacing; PCSX2 does not (bulk batch at end of retro_run). */
    private boolean usesAudioPacing() {
        return isFlycastCore() || coreName().contains("ppsspp");
    }

    /** Option overrides for the current core (or empty map). */
    private java.util.Map<String, String> currentOverrides() {
        String n = coreName();
        for (var e : CORE_OVERRIDES.entrySet()) {
            if (n.contains(e.getKey())) return e.getValue();
        }
        return java.util.Map.of();
    }

    private static void pinForever(Object ref) {
        if (ref != null && !PINNED_CALLBACKS.contains(ref)) {
            PINNED_CALLBACKS.add(ref);
        }
    }

    private void loadNativeImpl() {
        if (core != null) return;
        if (corePath == null) {
            LOGGER.error("Cannot load Windows libretro core: corePath is null");
            return;
        }

        // Own DLL copy per session => own globals per session.
        this.moduleSlot = CoreModulePool.acquire(corePath);
        if (moduleSlot == null) {
            LOGGER.error("Failed to prepare a module slot for {} — see CoreModulePool log "
                    + "(disk problem or limits.maxCoreSlots cap).", corePath.getFileName());
            return; // core stays null -> loadGame() returns false
        }
        String absPath = moduleSlot.slotPath().toAbsolutePath().toString();

        LOGGER.info("Native.load({})", absPath);
        try {
            long loadStartNs = System.nanoTime();
            if (needsVehHook() && supportsHwRender()) {
                captureJvmExceptionFilter();
                hookAddVeh();
                if (needsVehIsolation()) {
                    vehSessionHeld = true;
                    FLYCAST_VEH_SESSIONS.incrementAndGet();
                }
            }
            this.core = Native.load(absPath, LibretroBridge.class);
            // Ports of POSIX code (mkxp-z) open() their assets with the CRT default
            // mode. On Windows that default is TEXT, so reads stop at the first 0x1A
            // and binary game data is silently truncated. Fix it before retro_init(),
            // which is where such a core may already start reading.
            try {
                rawHlg().hlg_set_crt_binary();
            } catch (Throwable t) {
                LOGGER.warn("Could not switch CRT to binary open() mode: {}", t.getMessage());
            }
            int apiVersion = core.retro_api_version();
            // A few ms means a real LoadLibrary; 0 ms means JNA handed back the
            // still-mapped module (see needsFreshModule) — stale globals again.
            LOGGER.info("Core loaded in {} ms. API version: {} (corePath={})",
                    (System.nanoTime() - loadStartNs) / 1_000_000L, apiVersion, corePath.getFileName());
            if (!KEEP_LOADED.contains(this.core)) {
                KEEP_LOADED.add(this.core);
            }
            setupCallbacks();
            LOGGER.info("retro_init()");
            core.retro_init();
            coreInitialized = true;
            LOGGER.info("retro_init() returned. Core initialized.");
            try {
                var sysInfo = new LibretroBridge.RetroSystemInfo();
                core.retro_get_system_info(sysInfo);
                this.needFullpath = sysInfo.need_fullpath;
                LOGGER.info("Core system info: {} v{} (need_fullpath={}, valid_ext={})",
                        sysInfo.library_name, sysInfo.library_version,
                        sysInfo.need_fullpath, sysInfo.valid_extensions);
            } catch (Throwable t) {
                LOGGER.warn("retro_get_system_info failed: {}", t.getMessage());
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to load libretro core at {}: {}", absPath, t.getMessage(), t);
            LibretroBridge failed = this.core;
            if (failed != null) {
                try { failed.retro_deinit(); } catch (Throwable ignored) {}
                KEEP_LOADED.remove(failed);
            }
            endVehSessionIfLast();
            if (needsFreshModule()) {
                disposeCoreLibrary(failed); // slot module is dirty — unload for next try
            }
            CoreModulePool.release(moduleSlot);
            moduleSlot = null;
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
                // Never flag a frame for degenerate geometry: newFrame with an empty
                // frameBuffer makes drainHwFrame() skip the readback for as long as the
                // flag stays set (nobody can clear it), i.e. a permanently black screen.
                if (++zeroSizeFrames == 60) {
                    LOGGER.warn("Core reported {} video frames of {}x{} — the display stays "
                            + "black until it reports a real size", zeroSizeFrames, w, h);
                }
                return;
            }
            zeroSizeFrames = 0;
            synchronized (frameLock) {
                long dataAddr = data != null ? Pointer.nativeValue(data) : 0;
                boolean hwFb = hwRenderActive && (data == null || dataAddr == 1 || dataAddr == -1 || dataAddr == 0);
                if (!hwFb && (data == null || dataAddr == 0)) {
                    if (frameBuffer.length == w * h) newFrame = true;
                    return;
                }
                int len = w * h;
                // The HW path publishes its own array from drainHwFrame(), sizing
                // frameBuffer here would only fight it (a full-size alloc per frame).
                if (!hwFb && frameBuffer.length != len) frameBuffer = new int[len];
                int[] dst = frameBuffer;
                if (hwFb) {
                    // A probed or refused canvas is not a bigger picture (see
                    // applyGeometry): publish the size the core really renders at.
                    int fw = w, fh = h;
                    if (retroBaseW > 0) {
                        fw = retroProbeW > 0 ? retroBaseW : Math.min(w, retroBaseW);
                        fh = retroProbeH > 0 ? retroBaseH : Math.min(h, retroBaseH);
                    }
                    if (verboseLogs() && (fw != hwFrameW || fh != hwFrameH))
                        LOGGER.debug("[geo] video_cb {}x{} -> {}x{}", hwFrameW, hwFrameH, fw, fh);
                    hwFrameW = fw;
                    hwFrameH = fh;
                    hwFramePending = true;
                    return;
                }
                // FIX: software render (e.g. PPSSPP fallback without HW GL) may deliver
                // a frame of a different size than declared geometry
                // (480x272 instead of 960x540). The HW path updates width/height in
                // drainHwFrame(), but the software path did not — the client
                // interpreted a 480x272 buffer as 960x540 -> "doubled" image.
                if (this.width != w || this.height != h) {
                    LOGGER.info("Software frame size changed: {}x{} -> {}x{}",
                            this.width, this.height, w, h);
                    this.width = w;
                    this.height = h;
                }
                // end of fix

                // SOFTWARE path: bulk row read (one JNI per row, not per pixel)
                switch (pixelFormat) {
                    case LibretroBridge.RETRO_PIXEL_FORMAT_XRGB8888: {
                        int stride = (int) (pitch / 4);
                        if (swRowInts.length < w) swRowInts = new int[w];
                        for (int y = 0; y < h; y++) {
                            data.read((long) y * pitch, swRowInts, 0, w);
                            int base = y * w;
                            for (int x = 0; x < w; x++)
                                dst[base + x] = 0xFF000000 | (swRowInts[x] & 0x00FFFFFF);
                        }
                        break;
                    }
                    case LibretroBridge.RETRO_PIXEL_FORMAT_RGB565: {
                        if (swRowShorts.length < w) swRowShorts = new short[w];
                        for (int y = 0; y < h; y++) {
                            data.read((long) y * pitch, swRowShorts, 0, w);
                            int base = y * w;
                            for (int x = 0; x < w; x++) {
                                int raw = swRowShorts[x] & 0xFFFF;
                                int r = ((raw >> 11) & 0x1F) << 3;
                                int g = ((raw >> 5)  & 0x3F) << 2;
                                int b = ( raw        & 0x1F) << 3;
                                dst[base + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                            }
                        }
                        break;
                    }
                    case LibretroBridge.RETRO_PIXEL_FORMAT_0RGB1555: {
                        if (swRowShorts.length < w) swRowShorts = new short[w];
                        for (int y = 0; y < h; y++) {
                            data.read((long) y * pitch, swRowShorts, 0, w);
                            int base = y * w;
                            for (int x = 0; x < w; x++) {
                                int raw = swRowShorts[x] & 0xFFFF;
                                int r = ((raw >> 10) & 0x1F) << 3;
                                int g = ((raw >> 5)  & 0x1F) << 3;
                                int b = ( raw        & 0x1F) << 3;
                                dst[base + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
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

        this.audioBatchCallback = (data, frames) -> {
            int frameCount = (int) frames;
            if (frameCount > 0 && data != null) {
                int samples = frameCount * 2;
                synchronized (audioLock) {
                    appendAudio(data, samples);
                }
            }
            if (usesAudioPacing()) audioPacing.consumeSamples(frameCount);
            return frames;
        };
        core.retro_set_audio_sample_batch(audioBatchCallback);

        this.inputPollCallback = () -> { /* nothing to poll */ };
        core.retro_set_input_poll(inputPollCallback);

        LibretroBridge.RetroInputState inputStateCb = (port, device, index, id) -> {
            if (port < 0 || port >= MAX_PORTS) return 0;
            if (device == LibretroBridge.RETRO_DEVICE_KEYBOARD) {
                // One physical keyboard shared by all ports (boom3 polls it per port).
                return pollKey(id);
            }
            if (device == LibretroBridge.RETRO_DEVICE_MOUSE) {
                if (port != 0) return 0; // pointer/mouse only on port 0
                return pollMouse(id);
            }
            if (device == LibretroBridge.RETRO_DEVICE_JOYPAD) {
                if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_MASK) {
                    int mask = 0;
                    for (int i = 0; i < 21; i++) if (joypadState[port].get(i) != 0) mask |= (1 << i);
                    return (short) mask;
                }
                if (index == 0 && id >= 0 && id < 21) return (short) joypadState[port].get(id);
                return 0;
            }
            if (device == LibretroBridge.RETRO_DEVICE_POINTER) {
                if (port != 0) return 0; // pointer only on port 0
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
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_X) return (short) analogState[port].get(0);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_Y) return (short) analogState[port].get(1);
                }
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_X) return (short) analogState[port].get(2);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_ANALOG_Y) return (short) analogState[port].get(3);
                }
                if (index == LibretroBridge.RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
                    if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L ||
                            id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2) return (short) triggerState[port].get(0);
                    if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R ||
                            id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2) return (short) triggerState[port].get(1);
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

    // Scratch buffers for bulk software frame read (no per-pixel JNI).
    private int[] swRowInts = new int[0];
    private short[] swRowShorts = new short[0];

    private final java.util.concurrent.atomic.AtomicIntegerArray[] joypadState =
            new java.util.concurrent.atomic.AtomicIntegerArray[MAX_PORTS];
    private final java.util.concurrent.atomic.AtomicIntegerArray[] analogState =
            new java.util.concurrent.atomic.AtomicIntegerArray[MAX_PORTS];
    private final java.util.concurrent.atomic.AtomicIntegerArray[] triggerState =
            new java.util.concurrent.atomic.AtomicIntegerArray[MAX_PORTS];
    {
        for (int p = 0; p < MAX_PORTS; p++) {
            joypadState[p] = new java.util.concurrent.atomic.AtomicIntegerArray(21);
            analogState[p] = new java.util.concurrent.atomic.AtomicIntegerArray(4);
            triggerState[p] = new java.util.concurrent.atomic.AtomicIntegerArray(2);
        }
    }
    private volatile short pointerX;
    private volatile short pointerY;
    private volatile boolean pointerPressed;
    private int pointerPollLogLeft = 10;

    /** Flycast/PPSSPP sync timing via audio sample consumption. */
    private final AudioPacing audioPacing = new AudioPacing(44100);

    private static final int AUDIO_RING_CAP = 48000 * 2;
    private final short[] audioRing = new short[AUDIO_RING_CAP];
    private int audioWrite;
    private int audioCount;
    private final Object audioLock = new Object();
    private short[] audioBulkScratch = new short[4096];
    private volatile double audioSampleRate = 48000.0;

    private final java.util.Map<String, String> coreOptions = new java.util.LinkedHashMap<>();
    private final java.util.List<Memory> allocatedOptionMemory = new java.util.ArrayList<>();
    /**
     * Unknown env commands are logged once each. Cores poll some of them every frame
     * (mkxp-z asks for GET_DEVICE_POWER while it renders), and a WARN per call floods
     * the log from the emulator *and* the audio thread — the logging itself stutters.
     */
    private final java.util.Set<Integer> warnedEnvCmds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int coreOptionsVersion = 2;

    // libretro ABI: retro_core_option_value = 2 pointers; values[RETRO_NUM_CORE_OPTION_VALUES_MAX=128].
    // V1 def (retro_core_option_definition):    3 ptr + 128 values + 1 ptr default = 2080 (default @ 2072)
    // V2 def (retro_core_option_v2_definition): 6 ptr + 128 values + 1 ptr default = 2104 (default @ 2096)
    private static final int OPT_VALUE_SIZE    = Native.POINTER_SIZE * 2;
    private static final int OPT_DEF_V1_STRIDE = Native.POINTER_SIZE * 3 + 128 * OPT_VALUE_SIZE + Native.POINTER_SIZE;
    private static final int OPT_DEF_V2_STRIDE = Native.POINTER_SIZE * 6 + 128 * OPT_VALUE_SIZE + Native.POINTER_SIZE;
    private boolean audioBufferStatusRequested = false;
    /** EasyRPG and other async cores: frontend drives clock/audio via env callbacks. */
    private Pointer frameTimeCallbackFn;
    private long frameTimeReferenceUs;
    private long lastFrameTimeNs;
    private Pointer asyncAudioCallbackFn;
    private Pointer asyncAudioSetStateFn;
    private com.sun.jna.Function asyncAudioCallback;
    /**
     * Cadence for SET_AUDIO_CALLBACK. A threaded-audio core renders a fixed chunk per
     * call, not "whatever is due" — mkxp-z renders AUDIO_SLEEP (10 ms) worth
     * (al-util.h), 480 samples at 48 kHz. Pumping it from the frame loop handed the
     * core one chunk per video frame, so only ~60% of the samples ever existed: OpenAL
     * underranned and the sound was slow and choppy.
     */
    private static final long ASYNC_AUDIO_PERIOD_NS = 10_000_000L;
    /** Drives SET_AUDIO_CALLBACK while a core is loaded; null when no pump runs. */
    private volatile Thread asyncAudioPump;

    private boolean handleEnvironment(int cmd, Pointer data) {
        // Handle by raw code (switch on normalized base won't catch these):
        if (cmd == 0x10031) { // GET_FASTFORWARDING — sent every frame
            if (data != null) data.setByte(0, (byte) 0);
            return true;
        }
        if (cmd == 0x800004) return true; // private Flycast cmd
        // GET_AUDIO_VIDEO_ENABLE (47|EXPERIMENTAL) — LRPS2 mutes SPU2 without explicit "audio on".
        if (cmd == 0x1002f) {
            if (data != null) data.setInt(0, 3);
            return true;
        }
        // GET_DEVICE_POWER (77|EXPERIMENTAL). mkxp-z polls it repeatedly; leaving it
        // unanswered does not break anything, but the call must not fall through to the
        // unknown-command branch. We are a desktop: mains power, no battery estimate.
        if (cmd == LibretroEnvironment.GET_DEVICE_POWER) {
            if (data != null) {
                // retro_device_power { enum retro_power_state state; int seconds; int percent; }
                data.setInt(0, LibretroEnvironment.RETRO_POWERSTATE_PLUGGED_IN);
                data.setInt(4, LibretroEnvironment.RETRO_POWERSTATE_NO_ESTIMATE);
                data.setInt(8, 100);
            }
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
                        this.layoutWidth = w;
                        this.layoutHeight = h;
                        LOGGER.info("Core set geometry: {}x{}", w, h);
                        applyGeometry(w, h);
                    }
                }
                return true;
            case LibretroEnvironment.SET_SYSTEM_AV_INFO:
                // PPSSPP/PCSX2 report actual size and FPS here.
                if (data != null) {
                    int w = data.getInt(0);   // geometry.base_width
                    int h = data.getInt(4);   // geometry.base_height
                    // retro_system_timing.fps: double at offset 24
                    // (geometry 20 bytes + double alignment to 8).
                    double fps = data.getDouble(24);
                    if (w > 0 && h > 0) {
                        if (isCitraCore() && layoutWidth > 0 && layoutHeight > 0) {
                            // avInfo base is per-screen; keep dual-screen layout from SET_GEOMETRY.
                            this.width = layoutWidth;
                            this.height = layoutHeight;
                        } else {
                            this.width = w;
                            this.height = h;
                        }
                    }
                    // CRITICAL for PAL PS2 games: core changes 59.94 -> 50.0 here.
                    // Without this FrameSender runs PAL games ~20% too fast.
                    if (fps > 1.0 && fps < 1000.0) this.timingFps = fps;
                    double sr = data.getDouble(32);
                    if (sr > 8000.0 && sr < 384000.0) this.audioSampleRate = sr;
                    audioPacing.setSampleRate((int) Math.round(this.audioSampleRate));
                    LOGGER.info("SET_SYSTEM_AV_INFO: {}x{} @ {} fps, {} Hz", w, h, fps, sr);
                }
                return true;
            case LibretroEnvironment.GET_SYSTEM_DIRECTORY:
                if (isPcsx2Core() && loggedOptionKeys.add("__logged_system_dir__")) {
                    LOGGER.info("PCSX2 system dir -> {} (memory cards: {}/pcsx2/memcards/)",
                            systemDir, systemDir);
                }
                return returnCString(data, systemDir, true);
            case LibretroEnvironment.GET_SAVE_DIRECTORY:
                if (loggedOptionKeys.add("__logged_save_dir__")) {
                    LOGGER.info("Core save dir -> {} (SRAM/PPSSPP; PS2 uses system/pcsx2/memcards/)",
                            saveDir);
                }
                return returnCString(data, saveDir, false);
            case LibretroEnvironment.GET_LIBRETRO_PATH:
                if (data == null || corePath == null) return false;
                return returnCString(data, corePath.toAbsolutePath().toString(), false);
            case LibretroEnvironment.GET_VARIABLE:
                return handleGetVariable(data);
            case LibretroEnvironment.SET_VARIABLES: {
                if (data == null) return true;
                parseV1Strings(data);
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS: {
                if (data == null) return true;
                parseV1Structs(data, false);
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS_V2: {
                if (data == null) return true;
                parseV2Defs(data);
                return true;
            }
            case LibretroEnvironment.SET_CORE_OPTIONS_V2_INTL: {
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
                    if (k != null && !k.isEmpty()) coreOptions.put(k, v != null ? v : "");
                }
                return true;
            case LibretroEnvironment.SET_FRAME_TIME_CALLBACK:
                return handleSetFrameTimeCallback(data);
            case LibretroEnvironment.SET_AUDIO_CALLBACK:
                return handleSetAsyncAudioCallback(data);
            case LibretroEnvironment.SET_CORE_OPTIONS_DISPLAY:
            case LibretroEnvironment.SET_MINIMUM_AUDIO_LATENCY:
            case LibretroEnvironment.SET_INPUT_DESCRIPTORS:
            case LibretroEnvironment.SET_SERIALIZATION_QUIRKS:
                if ((cmd & LibretroEnvironment.EXPERIMENTAL) != 0) {
                    if (supportsHwRender()) {
                        LOGGER.info("SET_HW_SHARED_CONTEXT -> true (citra, raw=0x{})",
                                Integer.toHexString(cmd));
                        return true;
                    }
                    return false;
                }
                return true;
            case LibretroEnvironment.SET_MESSAGE:
                // retro_message { const char *msg; unsigned frames; } — cores report
                // load/init failures only here, so swallowing it turns a broken core
                // into a silent black screen.
                logCoreMessage(data, -1);
                return true;
            case LibretroEnvironment.SET_PERFORMANCE_LEVEL:
            case LibretroEnvironment.SET_SUBSYSTEM_INFO:
            case LibretroEnvironment.SET_CONTROLLER_INFO:
                return true;
            case LibretroEnvironment.SET_AUDIO_BUFFER_STATUS_CALLBACK:
                audioBufferStatusRequested = true;
                return true;
            case LibretroEnvironment.GET_AUDIO_VIDEO_ENABLE:
                if (data != null) data.setInt(0, 3);
                return true;
            case LibretroEnvironment.SET_SUPPORT_NO_GAME:
                if (data != null) data.setByte(0, (byte) 0);
                return true;
            case LibretroEnvironment.GET_PERF_INTERFACE:
                return false;
            case 51: // GET_INPUT_BITMASKS
                return true;
            case LibretroEnvironment.SET_HW_RENDER:
                return handleSetHwRender(data);
            case LibretroEnvironment.GET_PREFERRED_HW_RENDER:
                if (data != null && supportsHwRender()) {
                    data.setInt(0, LibretroEnvironment.RETRO_HW_CONTEXT_OPENGL_CORE);
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
                        return true;
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Native log cb unavailable: {}", t.getMessage());
                }
                return false;
            case 38:      // GET_USERNAME — return "no username"
                return false;
            case 60:      // SET_MESSAGE_EXT — log the text, we have no OSD to draw it on
                // retro_message_ext { const char *msg; unsigned level; unsigned duration; … }
                logCoreMessage(data, data != null ? data.getInt(Native.POINTER_SIZE) : -1);
                return true;
            case 45:      // GET_VFS_INTERFACE (45 | EXPERIMENTAL)
                // mkxp-z's PhysFS falls back to its own open()/opendir() without a
                // frontend VFS, and on Windows that cannot enumerate a directory
                // mount's root — every RPG Maker game then loads no assets at all.
                if (data == null) return false;
                try {
                    Pointer vfs = headlessGl().hlg_get_vfs_interface();
                    if (vfs != null && Pointer.nativeValue(vfs) != 0) {
                        int wanted = data.getInt(0);      // required_interface_version
                        data.setInt(0, 3);                // retro_vfs_interface v3
                        data.setPointer(Native.POINTER_SIZE, vfs);
                        pinForever(vfs);
                        LOGGER.info("GET_VFS_INTERFACE -> v3 (core asked for v{})", wanted);
                        return true;
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Native VFS interface unavailable: {}", t.getMessage());
                }
                return false;
            case LibretroEnvironment.SET_KEYBOARD_CALLBACK: {
                // Text-heavy cores (mkxp-z, DOSBox, …) never poll RETRO_DEVICE_KEYBOARD;
                // they keep their own key table and receive keys only through this
                // callback, so returning false means no keyboard input at all.
                if (data == null) return false;
                Pointer fn = data.getPointer(0);
                if (fn == null || Pointer.nativeValue(fn) == 0) return false;
                setKeyboardCallbackFn(fn);
                pinForever(fn);
                LOGGER.info("SET_KEYBOARD_CALLBACK registered @ {}", fn);
                return true;
            }
            case LibretroEnvironment.GET_DISK_CONTROL_INTERFACE_VERSION:
                if (data != null) data.setInt(0, 1);
                return true;
            case LibretroEnvironment.SET_DISK_CONTROL_INTERFACE:
            case LibretroEnvironment.SET_DISK_CONTROL_EXT_INTERFACE:
                return true;
            case 36:
                return true;
            case 40:
                return false;
            case LibretroEnvironment.SET_FASTFORWARDING_OVERRIDE:
                return true;
            case 50: {
                // GET_TARGET_REFRESH_RATE — mkxp-z adopts this as av_info.timing.fps
                // and otherwise hardcodes 60, which then fights the pacing the
                // emulator thread actually runs at.
                if (data == null) return false;
                data.setFloat(0, (float) (timingFps > 1.0 && timingFps < 1000.0 ? timingFps : 60.0));
                return true;
            }
            case 81: {
                // GET_TARGET_SAMPLE_RATE (mkxp-z specific) — the rate we play at, so
                // the core resamples its synthesised audio to it. Declining made it
                // fall back to its own SYNTH_SAMPLERATE (44100/48000 depending on
                // the build) while av_info advertised that same fallback back at us.
                if (data == null) return false;
                data.setInt(0, (int) Math.round(audioSampleRate));
                return true;
            }
            case 54:
            case 69:
            case 0x10030:
                return true;
            default:
                if (warnedEnvCmds.add(cmd)) {
                    LOGGER.warn("Unhandled env cmd {} (raw 0x{}, base {}) — repeats suppressed",
                            LibretroEnvironment.name(cmd), Integer.toHexString(cmd),
                            LibretroEnvironment.normalize(cmd));
                }
                return false;
        }
    }

    /**
     * Log a core-provided message. {@code level} is a RETRO_LOG_* value, or -1 when the
     * core used the legacy {@code retro_message} (which carries no severity).
     */
    private void logCoreMessage(Pointer data, int level) {
        if (data == null) return;
        try {
            String msg = readCString(data.getPointer(0));
            if (msg == null || msg.isBlank()) return;
            String text = msg.replace('\n', ' ').replace('\r', ' ').trim();
            if (level < 0 || level >= 2) lastCoreMessage = text;  // legacy box / warn / error
            switch (level) {
                case 3 -> LOGGER.error("Core message: {}", text);   // RETRO_LOG_ERROR
                case 2 -> LOGGER.warn("Core message: {}", text);    // RETRO_LOG_WARN
                default -> LOGGER.info("Core message: {}", text);
            }
        } catch (Throwable t) {
            LOGGER.debug("Unreadable core message: {}", t.getMessage());
        }
    }

    private boolean handleSetFrameTimeCallback(Pointer data) {
        if (data == null) return false;
        frameTimeCallbackFn = data.getPointer(0);
        frameTimeReferenceUs = Native.POINTER_SIZE == 8
                ? data.getLong(Native.POINTER_SIZE)
                : data.getInt(Native.POINTER_SIZE) & 0xFFFFFFFFL;
        lastFrameTimeNs = 0;
        LOGGER.info("SET_FRAME_TIME_CALLBACK: ref={}us", frameTimeReferenceUs);
        return frameTimeCallbackFn != null && Pointer.nativeValue(frameTimeCallbackFn) != 0;
    }

    private boolean handleSetAsyncAudioCallback(Pointer data) {
        if (data == null) return false;
        asyncAudioCallbackFn = data.getPointer(0);
        asyncAudioSetStateFn = data.getPointer(Native.POINTER_SIZE);
        asyncAudioCallback = asyncAudioCallbackFn == null
                || Pointer.nativeValue(asyncAudioCallbackFn) == 0
                ? null : com.sun.jna.Function.getFunction(asyncAudioCallbackFn);
        LOGGER.info("SET_AUDIO_CALLBACK registered");
        return asyncAudioCallback != null;
    }

    private void tickFrameTimeBeforeRun() {
        if (frameTimeCallbackFn == null || Pointer.nativeValue(frameTimeCallbackFn) == 0) return;
        long now = System.nanoTime();
        long usec = lastFrameTimeNs == 0
                ? (frameTimeReferenceUs > 0 ? frameTimeReferenceUs : 16_666L)
                : (now - lastFrameTimeNs) / 1_000L;
        lastFrameTimeNs = now;
        try {
            com.sun.jna.Function.getFunction(frameTimeCallbackFn).invokeVoid(new Object[]{ usec });
        } catch (Throwable t) {
            LOGGER.warn("frame_time callback failed: {}", t.getMessage());
        }
    }

    private void invokeAsyncAudioCallback() {
        com.sun.jna.Function fn = asyncAudioCallback;
        if (fn == null) return;
        try {
            fn.invokeVoid(new Object[0]);
        } catch (Throwable t) {
            LOGGER.warn("async audio callback failed: {}", t.getMessage());
        }
    }

    /** Start pumping SET_AUDIO_CALLBACK once the core asked for it. Idempotent. */
    private void startAsyncAudioPump() {
        if (asyncAudioPump != null) return;
        if (asyncAudioCallback == null) return;
        Thread t = new Thread(this::runAsyncAudioPump, "retro-audio-pump");
        t.setDaemon(true);
        asyncAudioPump = t;
        t.start();
        LOGGER.info("Async audio pump started ({} ms period)", ASYNC_AUDIO_PERIOD_NS / 1_000_000L);
    }

    /** Must run before retro_unload_game: the pump executes native core code. */
    private void stopAsyncAudioPump() {
        Thread t = asyncAudioPump;
        asyncAudioPump = null;
        if (t == null) return;
        t.interrupt();
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Async audio pump stopped");
    }

    private void runAsyncAudioPump() {
        Thread self = Thread.currentThread();
        long startNs = System.nanoTime();
        long chunks = 0;
        long calls = 0;
        long avgCostNs = 0;
        while (asyncAudioPump == self && !self.isInterrupted()) {
            // Closed loop on the chunk count, not on sleep deadlines: parking a whole
            // period oversleeps, which silently shaves samples off the stream.
            long due = (System.nanoTime() - startNs) / ASYNC_AUDIO_PERIOD_NS;
            long behind = due - chunks;
            if (behind <= 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(ASYNC_AUDIO_PERIOD_NS / 2);
                continue;
            }
            if (behind > 8) {
                // Huge stall (world load, GC): do not replay seconds of audio. Skip the
                // old chunks by moving the clock, keeping produced == due afterwards.
                startNs += (behind - 8) * ASYNC_AUDIO_PERIOD_NS;
                behind = 8;
            }
            long t0 = System.nanoTime();
            for (long c = 0; c < behind; c++) invokeAsyncAudioCallback();
            long dt = System.nanoTime() - t0;
            avgCostNs = calls == 0 ? dt : (avgCostNs * 15 + dt) / 16;
            calls++;
            chunks += behind;
            if (verboseLogs() && calls % 50 == 0) LOGGER.debug("async audio: {} chunks / {} calls, avg {}us/call, queued={}",
                    chunks, calls, avgCostNs / 1000, getAudioAvailable());
        }
    }

    private void setAsyncAudioEnabled(boolean enabled) {
        if (asyncAudioSetStateFn == null || Pointer.nativeValue(asyncAudioSetStateFn) == 0) return;
        try {
            com.sun.jna.Function.getFunction(asyncAudioSetStateFn).invokeVoid(new Object[]{ enabled ? 1 : 0 });
        } catch (Throwable t) {
            LOGGER.warn("async audio set_state failed: {}", t.getMessage());
        }
    }

    /**
     * SET_HW_RENDER: store pointers and fill the struct.
     * context_reset is called in loadGameImpl after successful retro_load_game.
     */
    private boolean handleSetHwRender(Pointer data) {
        if (data == null) return false;
        try {
            int ctxType = data.getInt(0x00);
            String ctxName = switch (ctxType) {
                case 0 -> "NONE"; case 1 -> "OPENGL"; case 2 -> "OPENGLES2";
                case 3 -> "OPENGL_CORE"; case 4 -> "OPENGLES3"; case 6 -> "VULKAN";
                case 7 -> "D3D11"; case 8 -> "D3D10"; case 9 -> "D3D12"; case 10 -> "D3D9";
                default -> "UNKNOWN(" + ctxType + ")";
            };
            LOGGER.info("Core requests HW render: context_type={} ({})", ctxType, ctxName);
            if (ctxType == 0) { hwRenderActive = false; return true; }
            if (!supportsHwRender()) return false;
            if (ctxType != 1 && ctxType != 3 && ctxType != 4) return false;

            int reqMajor = data.getInt(0x24);
            int reqMinor = data.getInt(0x28);
            if (reqMajor <= 0) { reqMajor = 3; reqMinor = 3; }
            // Citra GL renderer uses GL 4.x entry points (shader cache, etc.); 3.3 headless ctx crashes in context_reset.
            if (isCitraCore() && (ctxType == 1 || ctxType == 3)) {
                if (reqMajor < 4 || (reqMajor == 4 && reqMinor < 3)) {
                    reqMajor = 4;
                    reqMinor = 3;
                    LOGGER.info("Citra: requesting GL {}.{} headless context", reqMajor, reqMinor);
                }
            }
            hwReqGlMajor = reqMajor;
            hwReqGlMinor = reqMinor;

            if (!ensureHeadlessGl(ctxType, reqMajor, reqMinor)) return false;

            hwContextReset   = data.getPointer(0x08);
            hwContextDestroy = data.getPointer(0x30);

            if (headlessGl().hlg_make_current() == 0) {
                LOGGER.error("Failed to make WGL context current during SET_HW_RENDER");
                return false;
            }

            // The origin convention belongs to the core — read it instead of
            // insisting on one. mkxp-z and Citra declare top-left and would end up
            // upside down if we kept flipping every framebuffer unconditionally.
            hwBottomLeftOrigin = data.getByte(34) != 0;
            LOGGER.info("Core framebuffer origin: {}",
                    hwBottomLeftOrigin ? "bottom-left (flip on readback)" : "top-left (read as-is)");

            Pointer fbFn;
            Pointer procFn;
            if (isCitraCore()) {
                // Citra/glad loads hundreds of symbols from native — avoid JNA trampolines.
                fbFn = headlessGl().hlg_get_framebuffer_ptr();
                procFn = headlessGl().hlg_get_proc_address_ptr();
                pinForever(fbFn);
                pinForever(procFn);
            } else {
                pinForever(hwGetFb);
                pinForever(hwGetProc);
                fbFn = CallbackReference.getFunctionPointer(hwGetFb);
                procFn = CallbackReference.getFunctionPointer(hwGetProc);
            }
            data.setPointer(16, fbFn);
            data.setPointer(24, procFn);
            // Citra and boom3 need depth+stencil from the frontend — boom3 draws Doom 3's
            // stencil shadow volumes; the offscreen FBO has DEPTH24_STENCIL8 either way.
            boolean depthStencil = isCitraCore() || isBoom3Core();
            data.setByte(32, (byte) (depthStencil ? 1 : 0));  // depth
            data.setByte(33, (byte) (depthStencil ? 1 : 0));  // stencil
            // bottom_left_origin (34) is a core declaration — left untouched.
            data.setInt(36, hwGlMajor);              // version_major (actual context)
            data.setInt(40, hwGlMinor);              // version_minor

            headlessGl().hlg_dump_hw_render(data, 80);

            hwContextResetDone = false;
            hwRenderActive = true;
            LOGGER.info("SET_HW_RENDER accepted (GL {}.{}); fbFn={} procFn={}; context_reset deferred",
                    hwGlMajor, hwGlMinor, fbFn, procFn);
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to handle SET_HW_RENDER", t);
            return false;
        }
    }

    private Memory allocCString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        Memory m = new Memory(b.length + 1L);
        m.write(0, b, 0, b.length);
        m.setByte(b.length, (byte) 0);
        allocatedOptionMemory.add(m);
        return m;
    }

    private boolean returnCString(Pointer data, String s, boolean useSystemDirSlot) {
        if (data == null || s == null) return false;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        Memory m = new Memory(bytes.length + 1L);
        m.write(0, bytes, 0, bytes.length);
        m.setByte(bytes.length, (byte) 0);
        allocatedOptionMemory.add(m);
        data.setPointer(0, m);
        if (useSystemDirSlot) {
            if (systemDir != null && s.equals(systemDir) && persistentSystemDir == null)
                persistentSystemDir = m;
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
        // In v1 format "desc; opt1|opt2|..." default is first option, not last.
        int pipe = opts.indexOf('|');
        if (pipe < 0) return opts;
        return opts.substring(0, pipe).trim();
    }

    private void parseV1Structs(Pointer array, boolean v2) {
        if (array == null) return;
        final int KEY_OFF     = 0;
        final int stride      = v2 ? OPT_DEF_V2_STRIDE : OPT_DEF_V1_STRIDE;
        final int DEFAULT_OFF = stride - Native.POINTER_SIZE;
        long off = 0;
        int count = 0;
        while (true) {
            Pointer keyPtr;
            try { keyPtr = array.getPointer(KEY_OFF + off); } catch (Throwable t) { break; }
            if (keyPtr == null) break;
            String key;
            try { key = keyPtr.getString(0); } catch (Throwable t) { break; }
            if (key == null || key.isEmpty()) break;
            String def = "";
            try {
                Pointer defPtr = array.getPointer(DEFAULT_OFF + off);
                if (defPtr != null) def = defPtr.getString(0);
            } catch (Throwable t) { def = ""; }
            coreOptions.put(key, def != null ? def : "");
            LOGGER.debug("  core opt: {} = {}", key, def != null ? def : "");
            count++;
            off += stride;
            if (count > 256) break;
        }
        LOGGER.info("SET_CORE_OPTIONS: parsed {} defs, stored {} key(s)", count, count);
    }

    private void parseV2Defs(Pointer data) {
        // data = retro_core_options_v2*: { categories*, definitions* }
        Pointer defs = data.getPointer(Native.POINTER_SIZE);
        if (defs == null) defs = data.getPointer(0); // fallback for non-standard builds
        if (defs == null) return;
        parseV1Structs(defs, true);
    }

    private void parseV2Intl(Pointer data) {
        // data = retro_core_options_v2_intl*: { us*, local* }.
        // PCSX2 declares options ONLY via the intl variant — if not parsed,
        // coreOptions stays empty and GET_VARIABLE("pcsx2_bios") returns false
        // -> "Could not find any valid PS2 BIOS File" -> retro_load_game = false.
        Pointer us = data.getPointer(0);
        if (us != null) {
            parseV2Defs(us);
            LOGGER.info("SET_CORE_OPTIONS_V2_INTL: parsed US definitions");
            // DIAG: дамп ключей из нативного массива V2 (не legacy coreOptions).
            Pointer defs = us.getPointer(8);
            if (defs != null) {
                final long STRIDE = OPT_DEF_V2_STRIDE; // single source of truth (see parseV1Structs)
                int n = 0;
                for (long off = 0; n < 512; off += STRIDE, n++) {
                    Pointer keyP = defs.getPointer(off);
                    if (keyP == null) break;
                    Pointer defP = defs.getPointer(off + STRIDE - 8);
                    LOGGER.info("  option def: {} (default={})",
                            keyP.getString(0), defP != null ? defP.getString(0) : "");
                }
                LOGGER.info("SET_CORE_OPTIONS_V2_INTL: dumped {} option key(s)", n);
            }
        } else {
            LOGGER.debug("SET_CORE_OPTIONS_V2_INTL: null us definitions");
        }
    }

    private boolean handleGetVariable(Pointer data) {
        try {
            if (data == null) return false;
            if (Pointer.nativeValue(data) == 0) return false;
            Pointer keyPtr = data.getPointer(0);
            if (keyPtr == null) return false;
            long keyAddr = Pointer.nativeValue(keyPtr);
            if (keyAddr == 0 || keyAddr < 0x10000L) return false;
            String key = readCString(keyPtr);
            if (key == null || key.isEmpty()) return false;

            String val = resolveCoreOption(key);
            if (val == null || val.isEmpty()) return false;

            data.setPointer(Native.POINTER_SIZE, allocCString(val));
            if (loggedOptionKeys.add(key)) {
                LOGGER.info("Core option: {} = {}", key, val);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("GET_VARIABLE failed: {}", t.getMessage());
            return false;
        }
    }

    private String resolveCoreOption(String key) {
        java.util.Map<String, String> ov = currentOverrides();
        if (!ov.isEmpty()) {
            String override = ov.get(key);
            if (override != null) return override;
        }
        // Genesis Plus GX: force full-frame rendering — "single field" causes
        // visible vertical jitter on non-interlaced (digital) displays.
        if ("genesis_plus_gx_render".equals(key)) return "full frame";
        String val = coreOptions.get(key);
        if (val != null && !val.isEmpty()) return val;
        if (key.startsWith("pcsx_rearmed_")) return applyPcsxRearmedDefault(key);
        if (key.startsWith("flycast_") || key.startsWith("reicast_")) return applyFlycastDefault(key);
        if (key.startsWith("ppsspp_")) return applyPpssppDefault(key);
        if ("pcsx2_bios".equals(key) && systemDir != null) {
            String bios = Pcsx2BiosResolver.findFirstBiosFilenameFromSystemDir(
                    java.nio.file.Path.of(systemDir));
            if (bios != null) return bios;
        }
        if (key.startsWith("pcsx2_")) return applyPcsx2Default(key);
        return null;
    }

    private static String applyPcsxRearmedDefault(String key) {
        return VideoQualityPresets.pcsxRearmedDefault(key);
    }

    private static String applyFlycastDefault(String key) {
        return VideoQualityPresets.flycastDefault(key);
    }

    private static String applyPpssppDefault(String key) {
        return VideoQualityPresets.ppssppDefault(key);
    }

    private static String applyPcsx2Default(String key) {
        return VideoQualityPresets.pcsx2Default(key);
    }

    public boolean isCoreLoaded() { return core != null; }

    private volatile int width;
    private volatile int height;
    /** Full render target from SET_GEOMETRY (Citra dual-screen layout; avInfo base is smaller). */
    private volatile int layoutWidth;
    private volatile int layoutHeight;
    private volatile double timingFps = 60.0;
    private boolean gameLoaded;
    private volatile boolean pendingBatteryLoad;
    private Path pendingBatteryRomPath;

    private void resizeHwFramebuffer(int w, int h) {
        if (!headlessGlReady || w <= 0 || h <= 0) return;
        try {
            if (headlessGl().hlg_make_current() == 0) return;
            if (w != hwPbufW || h != hwPbufH) {
                headlessGl().hlg_resize(w, h);
                hwPbufW = w;
                hwPbufH = h;
                LOGGER.info("HW offscreen FBO resized to {}x{}", w, h);
            }
        } catch (Throwable t) {
            LOGGER.warn("HW FBO resize failed: {}", t.getMessage());
        }
    }

    /**
     * Reconcile an announced canvas with the size the core really renders at.
     * See the {@code retroBase*} field comment for why mkxp-z needs this at all.
     */
    private void applyGeometry(int w, int h) {
        if (!isMkxpzCore()) {
            // Every other core draws exactly what it announces.
            this.width = w;
            this.height = h;
            if (hwRenderActive) resizeHwFramebuffer(w, h);
            return;
        }
        if (retroBaseW <= 0 || retroBaseH <= 0) {
            // The first geometry is the screen the game renders at: the av_info
            // placeholder only sized an FBO nothing had drawn into yet.
            adoptBase(w, h, "first geometry");
            return;
        }
        if (w == retroBaseW && h == retroBaseH) {
            // A new canvas cycle starts at the base. Do not retain an old
            // rejection/probe when a later genuine resize reuses its dimensions.
            retroProbeW = retroProbeH = 0;
            retroProbeFrames = 0;
            retroRejectW = retroRejectH = -1;
            return;
        }
        if (w == retroRejectW && h == retroRejectH) return;   // already refused
        if (w > retroBaseW * RETRO_GROWTH_LIMIT || h > retroBaseH * RETRO_GROWTH_LIMIT) {
            refuseCanvas(w, h, "over " + RETRO_GROWTH_LIMIT + "x the base");
            return;
        }
        retroProbeW = w;
        retroProbeH = h;
        retroProbeFrames = 0;
        LOGGER.info("[geo] canvas {}x{} probed (core owns {}x{})",
                w, h, retroBaseW, retroBaseH);
    }

    private void adoptBase(int w, int h, String why) {
        retroBaseW = w;
        retroBaseH = h;
        retroProbeW = retroProbeH = 0;
        retroProbeFrames = 0;
        retroRejectW = retroRejectH = -1;
        this.width = w;
        this.height = h;
        if (hwRenderActive) resizeHwFramebuffer(w, h);
        LOGGER.info("[geo] base {}x{} ({})", w, h, why);
    }

    private void refuseCanvas(int w, int h, String why) {
        retroProbeW = retroProbeH = 0;
        retroProbeFrames = 0;
        if (w != retroRejectW || h != retroRejectH) {
            retroRejectW = w;
            retroRejectH = h;
            LOGGER.info("[geo] canvas {}x{} ignored — the core still owns {}x{} ({})",
                    w, h, retroBaseW, retroBaseH, why);
        }
    }

    /**
     * Settle a pending canvas probe against the core's own GL state. Only
     * {@code Graphics.resize_screen()} leaves a scissor box as large as the
     * announcement; a scaled window (resize_window / Graphics.scale) leaves the box
     * at the screen size it never changed, so a frame whose box never matches the
     * probe is a scaled window. A few frames are sampled instead of one so that a
     * game clipping with the scissor box is not mistaken for a resize.
     */
    private void verifyCanvasProbe() {
        if (retroProbeW <= 0 || retroProbeH <= 0) return;
        int pw = retroProbeW, ph = retroProbeH;
        // hlg_capture_info: out[14..17] = scissor box the core left behind.
        int[] cap = captureInfo();
        if (cap[16] == pw && cap[17] == ph) {
            adoptBase(pw, ph, "the core resized its screen");
        } else if (++retroProbeFrames >= RETRO_PROBE_FRAMES) {
            refuseCanvas(pw, ph, "the core kept its screen");
        }
    }

    /** Prefer SET_GEOMETRY layout for Citra; ignore inflated max_* caps elsewhere. */
    private int hwFboDim(LibretroBridge.RetroSystemAVInfo av, boolean width) {
        if (isCitraCore() && layoutWidth > 0 && layoutHeight > 0) {
            return width ? layoutWidth : layoutHeight;
        }
        int live = width ? this.width : this.height;
        if (live > 0) return live;
        return avMaxDim(av, width);
    }

    private static int avMaxDim(LibretroBridge.RetroSystemAVInfo av, boolean width) {
        int base = width ? av.geometry.base_width : av.geometry.base_height;
        int max = width ? av.geometry.max_width : av.geometry.max_height;
        // Citra reports max 5× base (e.g. 4000×4800); FBO only needs current geometry.
        if (base > 0 && max > base * 3) return base;
        return Math.max(max > 0 ? max : 0, base > 0 ? base : 0);
    }

    private boolean loadGameImpl(Path romPath) {
        if (core == null) {
            LOGGER.warn("loadGame({}) called before loadNative() — ignoring.", romPath);
            return false;
        }
        LOGGER.info("loadGame({}) [{}]", romPath, isFlycastCore() ? "FLYCAST" : coreName());
        clearCoreExit();

        if (isCitraCore()) {
            seedSharedCitraFiles();
        }

        LibretroBridge.RetroGameInfo info;
        try {
            info = buildGameInfo(romPath);
        } catch (Exception e) {
            LOGGER.error("Failed to build game info for {}: {}", romPath, e.getMessage(), e);
            return false;
        }

        boolean ok;
        try {
            if (isFlycastCore() && supportsHwRender()) {
                ensureHeadlessGl(LibretroEnvironment.RETRO_HW_CONTEXT_OPENGL_CORE,
                        hwReqGlMajor, hwReqGlMinor);
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

        if (hwRenderActive) {
            var avPre = new LibretroBridge.RetroSystemAVInfo();
            core.retro_get_system_av_info(avPre);
            int fboW = hwFboDim(avPre, true);
            int fboH = hwFboDim(avPre, false);
            if (verboseLogs()) LOGGER.debug("[geo] av_info(pre-load-end) base={}x{} max={}x{}",
                    avPre.geometry.base_width, avPre.geometry.base_height,
                    avPre.geometry.max_width, avPre.geometry.max_height);
            if (fboW > 0 && fboH > 0) resizeHwFramebuffer(fboW, fboH);
        }

        // Citra: defer context_reset to first runFrame (Linux pattern; glad init is fragile post-load).
        if (hwRenderActive && !isCitraCore() && !hwContextResetDone
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
                hwContextResetOk = true;
                LOGGER.info("context_reset completed");
            } catch (Throwable t) {
                LOGGER.error("context_reset crashed post-load: {}", t.getMessage(), t);
                teardownHwRender();
                return false;
            }
        }

        registerControllerPorts();

        var avInfo = new LibretroBridge.RetroSystemAVInfo();
        core.retro_get_system_av_info(avInfo);
        if (isCitraCore() && layoutWidth > 0 && layoutHeight > 0) {
            this.width = layoutWidth;
            this.height = layoutHeight;
        } else {
            this.width = avInfo.geometry.base_width;
            this.height = avInfo.geometry.base_height;
        }
        if (hwRenderActive) {
            int fboW = hwFboDim(avInfo, true);
            int fboH = hwFboDim(avInfo, false);
            if (verboseLogs()) LOGGER.debug("[geo] av_info(post) base={}x{} max={}x{}",
                    avInfo.geometry.base_width, avInfo.geometry.base_height,
                    avInfo.geometry.max_width, avInfo.geometry.max_height);
            if (fboW > 0 && fboH > 0) resizeHwFramebuffer(fboW, fboH);
        }
        // PPSSPP reports 0x0 at startup; real size arrives later via
        // SET_SYSTEM_AV_INFO. PSP default so texture is not 0x0 -> crash.
        if (this.width <= 0 || this.height <= 0) {
            this.width = 480;
            this.height = 272;
            LOGGER.info("AV info geometry was 0x0 — using PSP default 480x272 until SET_SYSTEM_AV_INFO");
        }
        this.timingFps = avInfo.timing_fps > 1.0 ? avInfo.timing_fps : 60.0;
        if (avInfo.timing_sample_rate > 8000.0) {
            this.audioSampleRate = avInfo.timing_sample_rate;
            audioPacing.setSampleRate((int) Math.round(this.audioSampleRate));
        }
        this.gameLoaded = true;
        audioPacing.reset();
        setAsyncAudioEnabled(true);
        LOGGER.info("Game loaded: {} ({}x{}, FPS={}, sampleRate={})",
                romPath.getFileName(), width, height,
                avInfo.timing_fps, avInfo.timing_sample_rate);

        if (saveDir != null && usesFrontendBatteryRam()) {
            pendingBatteryRomPath = romPath;
            pendingBatteryLoad = true;
        }
        if (hwRenderActive && isCitraCore()) {
            try {
                headlessGl().hlg_release();
                LOGGER.info("GL context released from load thread (citra)");
            } catch (Throwable t) {
                LOGGER.warn("hlg_release after citra load failed: {}", t.getMessage());
            }
        }
        return true;
    }

    private void registerControllerPorts() {
        if (isFlycastCore()) {
            // Flycast waits until all 4 ports are set before update_variables()
            // configures VMU in expansion slot A1 (see retro_set_controller_port_device).
            core.retro_set_controller_port_device(0, LibretroBridge.RETRO_DEVICE_JOYPAD);
            core.retro_set_controller_port_device(1, LibretroBridge.RETRO_DEVICE_JOYPAD);
            for (int port = 2; port < 4; port++) {
                core.retro_set_controller_port_device(port, LibretroBridge.RETRO_DEVICE_NONE);
            }
            LOGGER.info("Flycast: controller ports 0-1 registered as JOYPAD (VMU slot A1 active)");
        } else {
            core.retro_set_controller_port_device(0, LibretroBridge.RETRO_DEVICE_JOYPAD);
            core.retro_set_controller_port_device(1, LibretroBridge.RETRO_DEVICE_JOYPAD);
            LOGGER.info("Controller ports 0-1 registered as JOYPAD (co-op ready)");
        }
    }

    private LibretroBridge.RetroGameInfo loadedGameInfo;

    @Override public int getWidth()  { return width; }
    @Override public int getHeight() { return height; }

    /** Exact core FPS (59.94 for PSP) — for pacing in external FrameSender loop. */
    @Override public double getTimingFps() { return timingFps; }

    @Override public boolean prefersAvLockstep() { return isPcsx2Core(); }

    @Override public double getAudioSampleRate() { return audioSampleRate; }

    private void appendAudio(com.sun.jna.Pointer data, int samples) {
        if (audioBulkScratch.length < samples) audioBulkScratch = new short[samples];
        data.read(0, audioBulkScratch, 0, samples);
        for (int i = 0; i < samples; i++) {
            audioRing[audioWrite] = audioBulkScratch[i];
            audioWrite = (audioWrite + 1) % AUDIO_RING_CAP;
            if (audioCount < AUDIO_RING_CAP) audioCount++;
        }
    }

    /** Up to dst.length interleaved stereo 16-bit samples; returns short count. */
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
        synchronized (audioLock) {
            return audioCount;
        }
    }

    private LibretroBridge.RetroGameInfo buildGameInfo(Path romPath) throws java.io.IOException {
        var info = new LibretroBridge.RetroGameInfo();
        String abs = romPath.toAbsolutePath().normalize().toString();
        info.path = isFlycastCore() ? abs.replace('\\', '/') : abs;
        info.meta = "";
        String lower = romPath.toString().toLowerCase();
        // Disc-extension list kept as a safety net for cores that mis-report need_fullpath.
        boolean discImage = lower.endsWith(".cue") || lower.endsWith(".gdi")
                || lower.endsWith(".iso") || lower.endsWith(".chd")
                || lower.endsWith(".cso") || lower.endsWith(".mdf")
                || lower.endsWith(".nrg") || lower.endsWith(".img")
                || lower.endsWith(".pbp")
                // Doom 3 (boom3) mmaps its own pak files — never copy 400+ MB into a JNA Memory.
                || lower.endsWith(".pk4");
        // boom3 accepts a game directory as content — a folder has no bytes to slurp.
        boolean isDir = java.nio.file.Files.isDirectory(romPath);
        long size = isDir ? 0L : java.nio.file.Files.size(romPath);
        boolean pathOnly = needFullpath || discImage || isDir;
        if (pathOnly) {
            LOGGER.info("load mode: path-only (need_fullpath={}, disc_ext={}, size={})",
                    needFullpath, discImage, size);
            info.data = null;
            info.size = 0;
        } else if (size > MAX_IN_MEMORY_ROM) {
            // need_fullpath=false but unexpectedly large — refuse to slurp a stray ISO.
            LOGGER.warn("load mode: path-only fallback — need_fullpath=false but size={} exceeds cap {}",
                    size, MAX_IN_MEMORY_ROM);
            info.data = null;
            info.size = 0;
        } else {
            LOGGER.info("load mode: in-memory (need_fullpath=false, size={})", size);
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

    /** Safety cap: refuse to slurp anything this big into memory even if need_fullpath=false. */
    private static final long MAX_IN_MEMORY_ROM = 512L * 1024 * 1024;

    private void drainHwFrame() {
        synchronized (frameLock) {
            // Previous frame not consumed — no readback needed. The non-empty check
            // matters: newFrame over an empty frameBuffer must never block the readback.
            if (newFrame && frameBuffer.length > 0) return;
        }
        boolean pending; int w, h;
        synchronized (frameLock) {
            pending = hwFramePending;
            w = hwFrameW;
            h = hwFrameH;
            hwFramePending = false;
        }
        if (!pending || w <= 0 || h <= 0) return;
        try {
            headlessGl().hlg_make_current();
            int surfW = Math.max(w, hwPbufW);
            int surfH = Math.max(h, hwPbufH);
            if (surfW != hwPbufW || surfH != hwPbufH) {
                headlessGl().hlg_resize(surfW, surfH);
                hwPbufW = surfW;
                hwPbufH = surfH;
            }
            int reqLen = w * h;
            if (hwReadbackBuf == null || hwReadbackCap < reqLen) {
                hwReadbackBuf = new Memory((long) reqLen * 4L);
                hwReadbackCap = reqLen;
            }
            int[] vp = new int[4];
            headlessGl().hlg_read_pixels(vp, hwReadbackBuf, reqLen, w, h);
            // The DLL clamps the read rect to the real colour attachment, so the
            // size it reports is the size it actually wrote. Trusting w*h instead
            // published a short readback as a full frame and the untouched tail
            // showed up as stale rows at the bottom of the picture.
            int aw = vp[2] > 0 ? vp[2] : w;
            int ah = vp[3] > 0 ? vp[3] : h;
            if (aw > w) aw = w;
            if (ah > h) ah = h;
            int len = aw * ah;
            if (len <= 0 || (long) len > hwReadbackCap) {
                LOGGER.warn("HW frame {}x{} exceeds readback cap {}", aw, ah, hwReadbackCap);
                return;
            }
            hwActualW = aw;
            hwActualH = ah;
            // Bulk-read entire frame in one JNI call instead of getInt() per pixel.
            // Exact size on purpose: pollFrame() hands this array to the sender, and
            // an oversized buffer would read as a geometry mismatch there.
            if (hwReadbackInts.length != len) hwReadbackInts = new int[len];
            hwReadbackBuf.read(0, hwReadbackInts, 0, len);
            // Mask alpha in place — BGRA readback leaves 0x00 in the top byte and the
            // client treats the frame as opaque. One pass, no second buffer.
            for (int i = 0; i < len; i++)
                hwReadbackInts[i] |= 0xFF000000;
            // hlg_read_pixels always flips (it assumes GL bottom-left rows, which is
            // what the DLL reads). A top-left core already stores its framebuffer
            // screen-top-first, so undo the flip for those.
            if (!hwBottomLeftOrigin) flipRows(hwReadbackInts, aw, ah);
            diagFrame(hwReadbackInts, aw, ah, vp);
            synchronized (frameLock) {
                // Swap instead of copy: the sender copies out of frameBuffer under
                // this same lock, so the array it just released becomes the next
                // readback scratch. Saves a full-frame copy per frame.
                int[] scratch = frameBuffer.length == len ? frameBuffer : new int[len];
                frameBuffer = hwReadbackInts;
                hwReadbackInts = scratch;
                this.width = aw;
                this.height = ah;
                newFrame = true;
                zeroSizeFrames = 0;
            }
            // A canvas announced mid-frame is only real once the core proves it
            // resized (see applyGeometry); settle it after the frame is published.
            verifyCanvasProbe();
        } catch (Throwable t) {
            LOGGER.warn("HW readback failed: {}", t.getMessage());
        }
    }

    /** TEMP DIAG: how much of the readback is a repeat of one scanline. */
    private static int diagFrames;
    private static boolean diagBaselineLogged;
    private void diagFrame(int[] px, int w, int h, int[] vp) {
        // Deliberately not gated on verboseLogs(): the periodic diag is the only
        // way to see the capture geometry from the log file, and a native DLL's
        // stderr never lands there. Sampled every ~5 s, and quiet once a clean
        // frame has been reported — a banded frame keeps reporting.
        if (++diagFrames % 300 != 0) return;
        int last = h - 1;
        int firstRep = h;
        while (firstRep > 0 && sameRow(px, w, firstRep - 1, last)) firstRep--;
        int band = h > 0 ? (int) Math.round((h - firstRep) * 100.0 / h) : 0;
        int p0 = px[last * w], pm = px[last * w + w / 2], p1 = px[last * w + w - 1];
        // Bounding box of everything that differs from the bottom-right pixel (the
        // core clears the frame to one colour and blits its picture into a sub-rect,
        // so this box is the picture itself).
        int ref = px[last * w + w - 1];
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (px[row + x] == ref) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        // Vertical counterpart: a clamp-to-edge blit that overshoots the source
        // texture width leaves whole columns identical to the last valid one, so
        // the smallest run over the sampled rows is the honest measure.
        int colRep = w;
        int step = Math.max(1, h / 8);
        for (int y = 0; y < h; y += step) {
            int row = y * w, ref2 = px[row + w - 1], x = w - 1;
            while (x > 0 && px[row + x - 1] == ref2) x--;
            if (w - 1 - x < colRep) colRep = w - 1 - x;
        }
        int[] cap = captureInfo();
        // Expected on a healthy frame: the picture reaches the last row and the right
        // edge is not a repeated strip. The thresholds are generous on purpose — a UI
        // panel touches the edge with one colour for dozens of pixels legitimately
        // (Fire Ash: 63), while a clamp-to-edge blit leaves a *band* behind (34-50% of
        // the height and half the width). Being too strict here made the diag log a
        // line every 5 s forever, burying real warnings.
        if (h - firstRep <= Math.max(2, h / 100) && colRep <= Math.max(8, w / 4)
                && diagBaselineLogged) return;
        diagBaselineLogged = true;
        LOGGER.info("[diag-frame] {}x{} vp={},{},{},{} read={}x{} canvas={}x{} rb={}x{} "
                        + "att={}x{}(qok={},fbo={},qmiss={},heal={}) scissor={}({},{},{},{}) "
                        + "repeatRow={} band={}% repeatCol={} bbox={},{}..{},{} ({}x{}) "
                        + "row(last)={}/{}/{}",
                w, h, vp[0], vp[1], vp[2], vp[3], hwPbufW, hwPbufH,
                cap[0], cap[1], cap[2], cap[3], cap[4], cap[5],
                cap[6], cap[7], cap[8], cap[9],
                cap[18], cap[14], cap[15], cap[16], cap[17],
                firstRep, band, colRep, minX, minY, maxX, maxY,
                maxX - minX + 1, maxY - minY + 1,
                Integer.toHexString(p0), Integer.toHexString(pm), Integer.toHexString(p1));
        dumpFramePng(px, w, h);
    }

    /** Capture geometry snapshot from the DLL — see {@code hlg_capture_info}. */
    private int[] captureInfo() {
        int[] info = new int[21];
        try {
            if (hlgHandle != null) rawHlg().hlg_capture_info(hlgHandle, info);
        } catch (Throwable ignored) {}
        return info;
    }

    /**
     * TEMP DIAG: {@code RETRO_DUMP_FRAME=<path>} writes the raw readback to PNG so
     * the capture can be inspected without a screenshot of the in-world screen
     * (no perspective, no texture filtering). Keeps the first ten dumps.
     */
    private static final String DUMP_FRAME_TO = System.getenv("RETRO_DUMP_FRAME");
    private static int dumpedFrames;

    private static void dumpFramePng(int[] px, int w, int h) {
        if (DUMP_FRAME_TO == null || DUMP_FRAME_TO.isBlank() || dumpedFrames >= 10) return;
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, w, h, px, 0, w);
            Path base = Paths.get(DUMP_FRAME_TO);
            String name = base.getFileName().toString();
            Path dir = base.getParent() != null ? base.getParent() : Paths.get(".");
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : ".png";
            Path out = dir.resolve(String.format("%s-%02d%s", stem, ++dumpedFrames, ext));
            Files.createDirectories(dir);
            javax.imageio.ImageIO.write(img, "png", out.toFile());
            LOGGER.info("[diag-frame] dumped {}", out);
        } catch (Throwable t) {
            LOGGER.info("[diag-frame] dump failed: {}", t.getMessage());
        }
    }

    private static boolean sameRow(int[] px, int w, int a, int b) {
        int oa = a * w, ob = b * w;
        for (int x = 0; x < w; x++) if (px[oa + x] != px[ob + x]) return false;
        return true;
    }

    /** Reverse row order in place — turns GL bottom-up rows into screen order. */
    private static void flipRows(int[] px, int w, int h) {
        int[] row = new int[w];
        for (int y = 0, mirror = h - 1; y < mirror; y++, mirror--) {
            int top = y * w, bottom = mirror * w;
            System.arraycopy(px, top, row, 0, w);
            System.arraycopy(px, bottom, px, top, w);
            System.arraycopy(row, 0, px, bottom, w);
        }
    }

    /** TEMP DIAG: paint the FBO magenta before each frame to expose the painted region. */
    private static final boolean DIAG_CLEAR_FBO = System.getenv("RETRO_CLEAR_FBO") != null;

    private void runFrameImpl() {
        if (core != null && gameLoaded) {
            if (hwRenderActive) {
                try {
                    if (headlessGl().hlg_make_current() != 0 && !hwGpuLoggedOnEmulatorThread) {
                        hwGpuLoggedOnEmulatorThread = true;
                        LOGGER.info("Emulator thread GPU: {}", headlessGl().hlg_get_gpu_info());
                    }
                } catch (Throwable ignored) {}
                if (isCitraCore() && !hwContextResetDone
                        && hwContextReset != null && Pointer.nativeValue(hwContextReset) != 0) {
                    try {
                        if (headlessGl().hlg_make_current() == 0) {
                            LOGGER.error("Failed to make GL current before citra context_reset");
                            hwContextResetDone = true;
                        } else {
                            if (layoutWidth > 0 && layoutHeight > 0) {
                                resizeHwFramebuffer(layoutWidth, layoutHeight);
                            }
                            LOGGER.info("context_reset on emulator thread (citra) @ {} (FBO {}x{})",
                                    hwContextReset, hwPbufW, hwPbufH);
                            com.sun.jna.Function.getFunction(hwContextReset).invokeVoid(new Object[0]);
                            hwContextResetDone = true;
                            hwContextResetOk = true;
                            LOGGER.info("context_reset completed (citra)");
                        }
                    } catch (Throwable t) {
                        LOGGER.error("context_reset failed (citra): {}", t.getMessage(), t);
                        hwContextResetDone = true;
                        hwContextResetOk = false;
                    }
                }
            }
            if (isCitraCore() && hwRenderActive && !hwContextResetOk) {
                return;
            }
            try {
                tickFrameTimeBeforeRun();
                // Keyboard callbacks are only valid from the core thread; the client
                // presses keys on the Minecraft thread.
                flushKeyboardEvents();
                if (DIAG_CLEAR_FBO && hwRenderActive) headlessGl().hlg_debug_clear(0xFF00FF);
                core.retro_run();
                pollCoreExit();
                // Threaded audio runs on its own clock, not on the video frame — but it
                // must not start earlier either: mkxp boots inside the first retro_run
                // and would render a full second of audio into an undrained ring.
                startAsyncAudioPump();
                if (hwRenderActive) drainHwFrame();
                maybeLoadPendingBattery();
            } catch (Throwable t) {
                LOGGER.warn("retro_run threw: {}", t.getMessage(), t);
            }
        }
    }

    /** Drop a latch left over from the previous game; must run on the core thread. */
    private void clearCoreExit() {
        coreExitCode = 0;
        coreExitMessage = null;
        lastCoreMessage = null;
        HeadlessGLWin gl = HEADLESS_GL;
        if (gl == null) return;
        try { gl.hlg_core_exit_clear(); } catch (Throwable ignored) {}   // thread-keyed
    }

    /**
     * Read the exit the core latched while retro_run() was executing. The latch
     * is keyed by native thread, so this has to be called from the core thread —
     * which is exactly where the frame loop already sits.
     */
    private void pollCoreExit() {
        if (coreExitCode != 0) return;
        HeadlessGLWin gl = HEADLESS_GL;
        if (gl == null) return;
        try {
            int code = gl.hlg_core_exit_code();
            if (code == 0) return;
            String msg = lastCoreMessage;
            if (msg == null || msg.isBlank()) msg = gl.hlg_core_exit_message();
            coreExitCode = code;
            coreExitMessage = msg;
            LOGGER.warn("Core runtime ended ({}): {}", code, msg == null || msg.isBlank() ? "no detail" : msg);
        } catch (Throwable t) {
            LOGGER.debug("core-exit poll failed: {}", t.getMessage());
        }
    }

    @Override public int coreExitCode() { return coreExitCode; }

    @Override public String coreExitMessage() { return coreExitMessage; }

    private void maybeLoadPendingBattery() {
        if (!pendingBatteryLoad || saveDir == null || pendingBatteryRomPath == null) return;
        pendingBatteryLoad = false;
        com.retroconsole.platform.BatterySaveManager.loadIntoCore(
                this, pendingBatteryRomPath, java.nio.file.Path.of(saveDir));
    }

    // pollFrame / input: callable from any thread; does not touch GL.

    @Override
    public boolean pollFrame(int[] dst) {
        if (dst == null || dst.length == 0) return false;
        if (!newFrame) return false;
        synchronized (frameLock) {
            if (frameBuffer.length == 0) {
                newFrame = false; // nothing to hand out — do not keep the readback blocked
                return false;
            }
            int copyLen = Math.min(frameBuffer.length, dst.length);
            System.arraycopy(frameBuffer, 0, dst, 0, copyLen);
            newFrame = false;
            return true;
        }
    }

    @Override
    public void setButton(int buttonId, boolean pressed) {
        setButton(0, buttonId, pressed);
    }

    @Override
    public void setButton(int port, int buttonId, boolean pressed) {
        if (port < 0 || port >= MAX_PORTS) return;
        if (buttonId >= 0 && buttonId < 21) joypadState[port].set(buttonId, pressed ? 1 : 0);
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L) {
            joypadState[port].set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2, pressed ? 1 : 0);
            triggerState[port].set(0, pressed ? 32767 : 0);
        }
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R) {
            joypadState[port].set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2, pressed ? 1 : 0);
            triggerState[port].set(1, pressed ? 32767 : 0);
        }
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_L2) triggerState[port].set(0, pressed ? 32767 : 0);
        if (buttonId == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_R2) triggerState[port].set(1, pressed ? 32767 : 0);
    }

    @Override
    public void setPointer(short x, short y, boolean pressed) {
        this.pointerX = x;
        this.pointerY = y;
        this.pointerPressed = pressed;
        joypadState[0].set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_CURSOR_TOUCH, pressed ? 1 : 0);
    }

    @Override
    public void setAnalog(int stick, int axis, short value) {
        setAnalog(0, stick, axis, value);
    }

    @Override
    public void setAnalog(int port, int stick, int axis, short value) {
        if (port < 0 || port >= MAX_PORTS) return;
        int idx = stick * 2 + axis;
        if (idx >= 0 && idx < 4) analogState[port].set(idx, value);
    }

    @Override public void reset() { /* no-op */ }

    @Override
    public long getSerializeSize() {
        return onCoreThread(() -> {
            if (core == null || !gameLoaded) return 0L;
            try {
                return core.retro_serialize_size();
            } catch (Throwable t) {
                return 0L;
            }
        });
    }

    @Override
    public byte[] serialize() {
        return onCoreThread(() -> {
            if (core == null || !gameLoaded) return null;
            boolean pumping = asyncAudioPump != null;
            if (pumping) stopAsyncAudioPump();   // a state dump must not race audio_render
            try {
                long size = core.retro_serialize_size();
                if (size <= 0) return null;
                var mem = new Memory(size);
                if (core.retro_serialize(mem, size)) {
                    return mem.getByteArray(0, (int) size);
                }
                return null;
            } finally {
                if (pumping) startAsyncAudioPump();
            }
        });
    }

    @Override
    public boolean unserialize(byte[] data) {
        if (data == null) return false;
        return onCoreThread(() -> {
            if (core == null || !gameLoaded) return false;
            boolean pumping = asyncAudioPump != null;
            if (pumping) stopAsyncAudioPump();
            try {
                var mem = new Memory(data.length);
                mem.write(0, data, 0, data.length);
                return core.retro_unserialize(mem, data.length);
            } finally {
                if (pumping) startAsyncAudioPump();
            }
        });
    }

    @Override
    public byte[] getSaveRam() {
        return onCoreThread(() -> {
            if (core == null || !gameLoaded) return null;
            Pointer data = core.retro_get_memory_data(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
            long size = core.retro_get_memory_size(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
            if (data == null || size <= 0) return null;
            return data.getByteArray(0, (int) size);
        });
    }

    @Override
    public boolean setSaveRam(byte[] sram) {
        if (sram == null) return false;
        return onCoreThread(() -> setSaveRamImpl(sram));
    }

    private boolean setSaveRamImpl(byte[] sram) {
        if (core == null || !gameLoaded) return false;
        Pointer data = core.retro_get_memory_data(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        long size = core.retro_get_memory_size(LibretroBridge.RETRO_MEMORY_SAVE_RAM);
        if (data == null || size <= 0) return false;
        data.write(0, sram, 0, (int) Math.min(sram.length, size));
        return true;
    }

    private void closeImpl() {
        if (core == null) return;
        LOGGER.info("close(): shutting down libretro core {}", corePath);

        // The pump calls into the core; it must be gone before unload/deinit.
        stopAsyncAudioPump();

        if (gameLoaded) {
            try { core.retro_unload_game(); } catch (Throwable t) {
                LOGGER.warn("retro_unload_game failed: {}", t.getMessage());
            }
        }

        if (hwRenderActive && headlessGlReady) {
            try { headlessGl().hlg_make_current(); } catch (Throwable t) {
                LOGGER.warn("Failed to make WGL current for shutdown: {}", t.getMessage());
            }
        }

        /* HW GL context must be destroyed before retro_deinit (state machine). */
        destroyHwGlContext();

        if (gameLoaded || coreInitialized) {
            try { core.retro_deinit(); } catch (Throwable t) {
                LOGGER.warn("retro_deinit failed: {}", t.getMessage());
            }
        }

        endVehSessionIfLast();

        teardownHwRender();
        if (headlessGlReady) {
            try { headlessGl().hlg_destroy(); } catch (Throwable ignored) {}
            headlessGlReady = false;
        }
        if (hlgHandle != null) {
            try { rawHlg().hlg_free(hlgHandle); } catch (Throwable ignored) {}
            hlgHandle = null;
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
        audioPacing.reset();
        frameTimeCallbackFn = null;
        asyncAudioCallbackFn = null;
        asyncAudioSetStateFn = null;
        asyncAudioCallback = null;
        lastFrameTimeNs = 0;
        frameTimeReferenceUs = 0;

        // Flycast/boom3 globals are only reset by a fresh LoadLibrary — unload
        // the slot module. Order above is already correct: unload_game ->
        // context_destroy -> retro_deinit -> endVehSessionIfLast() (VEH must be
        // dead BEFORE FreeLibrary of the last session).
        if (needsFreshModule()) {
            disposeCoreLibrary(closed);
        }
        // PCSX2/others: do NOT dispose — LRPS2 may keep an MTGS thread after a
        // skipped context_destroy; FreeLibrary would crash the JVM. Their slot
        // reuses the same module (deinit->init already works today).
        CoreModulePool.release(moduleSlot);
        moduleSlot = null;

        LOGGER.info("close(): clean shutdown complete");
    }

    public String getSystemDir() { return systemDir; }
    public String getSaveDir()   { return saveDir; }
}