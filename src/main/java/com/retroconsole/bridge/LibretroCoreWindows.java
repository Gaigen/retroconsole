package com.retroconsole.bridge;

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
    void    hlg_debug_fbo(Pointer h);
    String  hlg_get_gpu_info(Pointer h);

    // Process-wide (no handle)
    void    hlg_dump_hw_render(Pointer data, int size);
    long    hlg_get_framebuffer();
    Pointer hlg_get_proc_address(String sym);
    Pointer hlg_get_framebuffer_ptr();
    Pointer hlg_get_proc_address_ptr();
    Pointer hlg_get_log_cb_ptr();
    /** Capture HotSpot UEF before Flycast core loads (diagnostic, optional). */
    void    hlg_capture_jvm_filter();
    /** Hook RtlAddVectoredExceptionHandler so Flycast VEH goes to tail. */
    void    hlg_hook_addveh();
    /** Reset isolated VEH state between core sessions (dispatcher + fly[]). */
    void    hlg_reset_veh_session();
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
        all.put("citra", VideoQualityPresets.citraOverrides());
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
        void hlg_debug_fbo() {
            if (hlgHandle != null) rawHlg().hlg_debug_fbo(hlgHandle);
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
        hwReadbackBuf = null;
        hwReadbackCap = 0;
        hwReadbackInts = new int[0];
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

    private String coreName() {
        return corePath == null ? "" : corePath.getFileName().toString().toLowerCase();
    }

    private boolean isFlycastCore() {
        return coreName().contains("flycast");
    }

    private boolean isCitraCore() {
        return coreName().contains("citra");
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
            if (needsVehHook() && supportsHwRender()) {
                captureJvmExceptionFilter();
                hookAddVeh();
                if (needsVehIsolation()) {
                    vehSessionHeld = true;
                    FLYCAST_VEH_SESSIONS.incrementAndGet();
                }
            }
            this.core = Native.load(absPath, LibretroBridge.class);
            int apiVersion = core.retro_api_version();
            LOGGER.info("Core loaded. API version: {} (corePath={})", apiVersion, corePath.getFileName());
            if (!KEEP_LOADED.contains(this.core)) {
                KEEP_LOADED.add(this.core);
            }
            setupCallbacks();
            LOGGER.info("retro_init()");
            core.retro_init();
            coreInitialized = true;
            LOGGER.info("retro_init() returned. Core initialized.");
        } catch (Throwable t) {
            LOGGER.error("Failed to load libretro core at {}: {}", absPath, t.getMessage(), t);
            LibretroBridge failed = this.core;
            if (failed != null) {
                try { failed.retro_deinit(); } catch (Throwable ignored) {}
                KEEP_LOADED.remove(failed);
            }
            endVehSessionIfLast();
            if (isFlycastCore()) {
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
            if (port != 0) return 0;
            if (device == LibretroBridge.RETRO_DEVICE_JOYPAD) {
                if (id == LibretroBridge.RETRO_DEVICE_ID_JOYPAD_MASK) {
                    int mask = 0;
                    for (int i = 0; i < 21; i++) if (joypadState.get(i) != 0) mask |= (1 << i);
                    return (short) mask;
                }
                if (index == 0 && id >= 0 && id < 21) return (short) joypadState.get(id);
                return 0;
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
    private int coreOptionsVersion = 2;
    private boolean audioBufferStatusRequested = false;

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
                        this.width = w;
                        this.height = h;
                        LOGGER.info("Core set geometry: {}x{}", w, h);
                        if (hwRenderActive) resizeHwFramebuffer(w, h);
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
            case LibretroEnvironment.SET_CORE_OPTIONS_DISPLAY:
            case LibretroEnvironment.SET_MINIMUM_AUDIO_LATENCY:
            case LibretroEnvironment.SET_AUDIO_CALLBACK:
            case LibretroEnvironment.SET_INPUT_DESCRIPTORS:
            case LibretroEnvironment.SET_MESSAGE:
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
            case 60:      // SET_MESSAGE_EXT — accept but ignore OSD
                return true;
            case 45:
            case 0x1002e:
                return false;
            case LibretroEnvironment.SET_KEYBOARD_CALLBACK:
                return false;
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
            case 54:
            case 69:
            case 0x10030:
                return true;
            default:
                LOGGER.warn("Unhandled env cmd {} (raw 0x{}, base {})",
                        LibretroEnvironment.name(cmd), Integer.toHexString(cmd),
                        LibretroEnvironment.normalize(cmd));
                return false;
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

            Pointer fbFn;
            Pointer procFn;
            if (isCitraCore()) {
                // Citra/glad loads hundreds of symbols from native — avoid JNA trampolines.
                fbFn = headlessGl().hlg_get_framebuffer_ptr();
                procFn = headlessGl().hlg_get_proc_address_ptr();
                pinForever(fbFn);
                pinForever(procFn);
                data.setByte(32, (byte) 1);          // depth (offscreen FBO has depth_rb)
                data.setByte(33, (byte) 1);          // stencil
            } else {
                pinForever(hwGetFb);
                pinForever(hwGetProc);
                fbFn = CallbackReference.getFunctionPointer(hwGetFb);
                procFn = CallbackReference.getFunctionPointer(hwGetProc);
            }
            data.setPointer(16, fbFn);
            data.setPointer(24, procFn);
            // Citra uses top-left framebuffer semantics; PPSSPP/PCSX2 expect bottom-left.
            data.setByte(34, (byte) (isCitraCore() ? 0 : 1));
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
        final int DEFAULT_OFF = v2 ? 536 : 16;
        long off = 0;
        int count = 0;
        while (true) {
            String key;
            try { key = array.getString(KEY_OFF + off); } catch (Throwable t) { break; }
            if (key == null || key.isEmpty()) break;
            String def;
            try { def = array.getString(DEFAULT_OFF + off); } catch (Throwable t) { def = ""; }
            coreOptions.put(key, def != null ? def : "");
            count++;
            off += 544;
            if (count > 256) break;
        }
        LOGGER.info("SET_CORE_OPTIONS: stored {} key(s)", count);
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
                final long STRIDE = 6L * 8 + 128L * 16 + 8; // 2104
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
            for (int port = 1; port < 4; port++) {
                core.retro_set_controller_port_device(port, LibretroBridge.RETRO_DEVICE_NONE);
            }
            LOGGER.info("Flycast: all controller ports registered (VMU slot A1 active)");
        } else {
            core.retro_set_controller_port_device(0, LibretroBridge.RETRO_DEVICE_JOYPAD);
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
        boolean discImage = lower.endsWith(".cue") || lower.endsWith(".gdi")
                || lower.endsWith(".iso") || lower.endsWith(".chd")
                || lower.endsWith(".cso") || lower.endsWith(".mdf")
                || lower.endsWith(".nrg") || lower.endsWith(".img")
                || lower.endsWith(".pbp");
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
        synchronized (frameLock) {
            if (newFrame) return; // previous frame not consumed — no readback needed
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
            int len = w * h;
            if (hwReadbackBuf == null || hwReadbackCap < len) {
                hwReadbackBuf = new Memory((long) len * 4L);
                hwReadbackCap = len;
            }
            int[] vp = new int[4];
            headlessGl().hlg_read_pixels(vp, hwReadbackBuf, len, w, h);
            int aw = w;
            int ah = h;
            if (len <= 0 || (long) len > hwReadbackCap) {
                LOGGER.warn("HW frame {}x{} exceeds readback cap {}", aw, ah, hwReadbackCap);
                return;
            }
            hwActualW = aw;
            hwActualH = ah;
            // Bulk-read entire frame in one JNI call instead of getInt() per pixel.
            if (hwReadbackInts.length < len) hwReadbackInts = new int[len];
            hwReadbackBuf.read(0, hwReadbackInts, 0, len);
            synchronized (frameLock) {
                if (frameBuffer.length != len) frameBuffer = new int[len];
                int[] dst = frameBuffer;
                for (int i = 0; i < len; i++)
                    dst[i] = 0xFF000000 | (hwReadbackInts[i] & 0x00FFFFFF);
                this.width = aw;
                this.height = ah;
                newFrame = true;
            }
        } catch (Throwable t) {
            LOGGER.warn("HW readback failed: {}", t.getMessage());
        }
    }

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
                core.retro_run();
                if (hwRenderActive) drainHwFrame();
                maybeLoadPendingBattery();
            } catch (Throwable t) {
                LOGGER.warn("retro_run threw: {}", t.getMessage(), t);
            }
        }
    }

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
            if (frameBuffer.length == 0) return false;
            int copyLen = Math.min(frameBuffer.length, dst.length);
            System.arraycopy(frameBuffer, 0, dst, 0, copyLen);
            newFrame = false;
            return true;
        }
    }

    @Override
    public void setButton(int buttonId, boolean pressed) {
        if (buttonId >= 0 && buttonId < 21) joypadState.set(buttonId, pressed ? 1 : 0);
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
    public void setPointer(short x, short y, boolean pressed) {
        this.pointerX = x;
        this.pointerY = y;
        this.pointerPressed = pressed;
        joypadState.set(LibretroBridge.RETRO_DEVICE_ID_JOYPAD_CURSOR_TOUCH, pressed ? 1 : 0);
    }

    @Override
    public void setAnalog(int stick, int axis, short value) {
        int idx = stick * 2 + axis;
        if (idx >= 0 && idx < 4) analogState.set(idx, value);
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
            long size = core.retro_serialize_size();
            if (size <= 0) return null;
            var mem = new Memory(size);
            if (core.retro_serialize(mem, size)) {
                return mem.getByteArray(0, (int) size);
            }
            return null;
        });
    }

    @Override
    public boolean unserialize(byte[] data) {
        if (data == null) return false;
        return onCoreThread(() -> {
            if (core == null || !gameLoaded) return false;
            var mem = new Memory(data.length);
            mem.write(0, data, 0, data.length);
            return core.retro_unserialize(mem, data.length);
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

        // Flycast globals are only reset by a fresh LoadLibrary — unload the
        // slot module. Order above is already correct: unload_game ->
        // context_destroy -> retro_deinit -> endVehSessionIfLast() (VEH must be
        // dead BEFORE FreeLibrary of the last session).
        if (isFlycastCore()) {
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