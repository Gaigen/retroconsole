package com.retroconsole.bridge;

import com.retroconsole.platform.CoreSavePolicy;
import com.retroconsole.platform.OsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Cross-platform facade around a loaded libretro core.
 *
 * <p>This class is intentionally minimal: it owns the {@link #load(Path, String, String)}
 * factory that picks the right platform implementation (currently {@link LibretroCoreLinux}
 * and {@link LibretroCoreWindows}) and exposes the abstract surface that the rest of the
 * mod (CoreManager, LibretroRuntime, ServerConsoles, SmokeTest) depends on.
 *
 * <p>All JNA / native code lives in the platform subclasses. Windows today has a stub
 * implementation — calls to {@link #loadGame(Path)} will return {@code false} and the
 * emulator thread will simply not start. The point of this split is to keep the Linux
 * implementation honest (no platform forks inside its methods) and to give Windows a
 * clean, independent starting point.
 */
public abstract class LibretroCore implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroCore");

    /** Last-resort fallback for unknown platforms — same behaviour as the Windows stub. */
    private static final LibretroCore DISABLED = new LibretroCore(null) {
        @Override public boolean loadGame(Path romPath) { return false; }
        @Override public void runFrame() { }
        @Override public boolean pollFrame(int[] dst) { return false; }
        @Override public int getWidth() { return 0; }
        @Override public int getHeight() { return 0; }
        @Override public void setButton(int buttonId, boolean pressed) { }
        @Override public void setPointer(short x, short y, boolean pressed) { }
        @Override public void setAnalog(int stick, int axis, short value) { }
        @Override public void reset() { }
        @Override public byte[] serialize() { return null; }
        @Override public boolean unserialize(byte[] data) { return false; }
        @Override public byte[] getSaveRam() { return null; }
        @Override public boolean setSaveRam(byte[] sram) { return false; }
        @Override public int readAudio(short[] dst) { return readAudio(dst, dst.length); }
        @Override public int readAudio(short[] dst, int maxShorts) { return 0; }
        @Override public double getAudioSampleRate() { return 48000.0; }
        @Override public double getTimingFps() { return 60.0; }
        @Override public boolean prefersAvLockstep() { return false; }
        @Override public void close() { }
    };

    /** The core binary the user requested, for diagnostics. Subclasses may use this. */
    protected final Path corePath;

    protected LibretroCore(Path corePath) {
        this.corePath = corePath;
    }

    /**
     * Load a libretro core and return the appropriate platform implementation.
     *
     * <p>On Linux this delegates to {@link LibretroCoreLinux}, which is the original
     * native behaviour (Headless GL, Flycast mprotect patches, etc.). On Windows it
     * currently returns a stub that refuses every game — Windows emulator support is a
     * separate piece of work that does not belong in the Linux code path.
     */
    public static LibretroCore load(Path corePath) {
        return load(corePath, null, null);
    }

    public static LibretroCore load(Path corePath, String systemDir, String saveDir) {
        LOGGER.info("Loading libretro core: {} on {}", corePath, OsUtil.current());

        switch (OsUtil.current()) {
            case LINUX:
                return LibretroCoreLinux.create(corePath, systemDir, saveDir);

            case WINDOWS:
                LibretroCoreWindows win = new LibretroCoreWindows(corePath, systemDir, saveDir);
                win.loadNative(); // Step 1: JNA-load the .dll. No further work yet.
                return win;

            case MACOS:
            case UNKNOWN:
            default:
                LOGGER.warn("Unsupported platform {} for libretro core {} — returning stub.",
                        OsUtil.current(), corePath);
                return DISABLED;
        }
    }

    // --- Abstract surface used by the rest of the mod ---

    public abstract boolean loadGame(Path romPath);

    public abstract void runFrame();

    public abstract boolean pollFrame(int[] dst);

    public abstract int getWidth();

    public abstract int getHeight();

    /**
     * Non-zero when the core's own runtime ended without a libretro SHUTDOWN:
     * 1 = the game exited, 2 = it threw. Ports such as mkxp-z tear their sandbox
     * down inside retro_run() and only say so in their log, so a console that
     * ignores this keeps streaming the last (black) frame forever.
     */
    public int coreExitCode() { return 0; }

    /** Human-readable reason reported with {@link #coreExitCode()}, or null. */
    public String coreExitMessage() { return null; }

    public abstract void setButton(int buttonId, boolean pressed);

    public abstract void setPointer(short x, short y, boolean pressed);

    public abstract void setAnalog(int stick, int axis, short value);

    /** Port-aware button (co-op). Default delegates to port-0 variant. */
    public void setButton(int port, int buttonId, boolean pressed) {
        if (port == 0) setButton(buttonId, pressed);
    }

    /** Port-aware analog (co-op). Default delegates to port-0 variant. */
    public void setAnalog(int port, int stick, int axis, short value) {
        if (port == 0) setAnalog(stick, axis, value);
    }

    // --- Keyboard / mouse -------------------------------------------------
    //
    // Cores that poll RETRO_DEVICE_KEYBOARD (boom3/Doom 3, DOSBox, …) read raw
    // RETROK codes; cores that poll RETRO_DEVICE_MOUSE read relative deltas plus
    // button/wheel state. The state lives here so both platform bridges only
    // have to translate the poll into a value — no duplicated arrays.

    /** Highest RETROK code we keep state for (F-keys and modifiers sit above 300). */
    public static final int KEY_STATE_SIZE = 512;

    /** Bit index in the client mouse mask -> libretro mouse button id. */
    public static final int[] MOUSE_MASK_TO_ID = {
            LibretroBridge.RETRO_DEVICE_ID_MOUSE_LEFT,
            LibretroBridge.RETRO_DEVICE_ID_MOUSE_RIGHT,
            LibretroBridge.RETRO_DEVICE_ID_MOUSE_MIDDLE,
            LibretroBridge.RETRO_DEVICE_ID_MOUSE_BUTTON_4,
            LibretroBridge.RETRO_DEVICE_ID_MOUSE_BUTTON_5,
    };

    private final java.util.concurrent.atomic.AtomicIntegerArray keyState =
            new java.util.concurrent.atomic.AtomicIntegerArray(KEY_STATE_SIZE);
    private final java.util.concurrent.atomic.AtomicIntegerArray mouseButtonState =
            new java.util.concurrent.atomic.AtomicIntegerArray(16);
    private final java.util.concurrent.atomic.AtomicInteger mouseDx =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger mouseDy =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger mouseWheelUp =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger mouseWheelDown =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Some cores (mkxp-z and other text-input cores) never poll RETRO_DEVICE_KEYBOARD.
     * They register a {@code retro_keyboard_callback_t} through SET_KEYBOARD_CALLBACK
     * and keep their own key table instead, so without the callback they see no keys
     * at all. The pointer is the core's function; we call it from the core thread.
     */
    private volatile com.sun.jna.Pointer keyboardCallbackFn;
    private final java.util.Queue<int[]> keyboardEvents = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** Bound on pending events: a held key only ever queues press+release. */
    private static final int KEY_EVENT_QUEUE_CAP = 128;

    /** Native {@code retro_keyboard_callback_t} handed to us by the core. */
    protected final void setKeyboardCallbackFn(com.sun.jna.Pointer fn) {
        this.keyboardCallbackFn = fn;
    }

    public boolean hasKeyboardCallback() {
        com.sun.jna.Pointer fn = keyboardCallbackFn;
        return fn != null && com.sun.jna.Pointer.nativeValue(fn) != 0;
    }

    /** Key press/release, {@code retroKey} is a RETROK code (see KeyboardMapper). */
    public void setKey(int retroKey, boolean pressed) {
        if (retroKey < 0 || retroKey >= KEY_STATE_SIZE) return;
        keyState.set(retroKey, pressed ? 1 : 0);
        if (hasKeyboardCallback() && keyboardEvents.size() < KEY_EVENT_QUEUE_CAP)
            keyboardEvents.add(new int[]{retroKey, pressed ? 1 : 0});
    }

    /**
     * Deliver queued key events to the core. Runs on the core-owning thread just
     * before {@code retro_run()} — libretro allows the callback only from there,
     * and the client presses keys on the Minecraft thread.
     */
    protected final void flushKeyboardEvents() {
        com.sun.jna.Pointer fn = keyboardCallbackFn;
        if (fn == null || com.sun.jna.Pointer.nativeValue(fn) == 0) return;
        com.sun.jna.Function cb = com.sun.jna.Function.getFunction(fn);
        int[] ev;
        for (int budget = 64; budget > 0 && (ev = keyboardEvents.poll()) != null; budget--) {
            boolean down = ev[1] != 0;
            try {
                cb.invokeVoid(new Object[]{(byte) (down ? 1 : 0), ev[0],
                        characterFor(ev[0], down), (short) modifiersMask()});
            } catch (Throwable t) {
                // A throwing core callback must not take the frame loop with it.
                return;
            }
        }
    }

    /** RETROKMOD_* bits for the keys currently held. */
    private int modifiersMask() {
        int m = 0;
        if (keyState.get(304) != 0 || keyState.get(303) != 0)
            m |= LibretroBridge.RETROKMOD_SHIFT;   // RETROK_LSHIFT / RETROK_RSHIFT
        if (keyState.get(306) != 0 || keyState.get(305) != 0)
            m |= LibretroBridge.RETROKMOD_CTRL;    // RETROK_LCTRL / RETROK_RCTRL
        if (keyState.get(308) != 0 || keyState.get(307) != 0)
            m |= LibretroBridge.RETROKMOD_ALT;     // RETROK_LALT / RETROK_RALT
        return m;
    }

    /** Printable RETROK -> the character the key produces, 0 for everything else. */
    private int characterFor(int retroKey, boolean down) {
        if (!down || retroKey < ' ' || retroKey > '~') return 0;
        if (retroKey >= 'a' && retroKey <= 'z'
                && (modifiersMask() & LibretroBridge.RETROKMOD_SHIFT) != 0)
            return retroKey - ('a' - 'A');
        return retroKey;
    }

    /** Accumulate relative mouse movement; the core drains it on the next poll. */
    public void setMouseDelta(int dx, int dy) {
        if (dx != 0) mouseDx.addAndGet(dx);
        if (dy != 0) mouseDy.addAndGet(dy);
    }

    /** Full button state as a bit mask: bit0 left, bit1 right, bit2 middle, bit3/4 back/forward. */
    public void setMouseButtons(int mask) {
        for (int i = 0; i < MOUSE_MASK_TO_ID.length; i++) {
            int want = (mask >> i) & 1;
            int id = MOUSE_MASK_TO_ID[i];
            if (mouseButtonState.get(id) != want) mouseButtonState.set(id, want);
        }
    }

    /** Wheel detents: positive = up, negative = down. */
    public void setMouseWheel(int direction) {
        if (direction > 0) mouseWheelUp.addAndGet(direction);
        else if (direction < 0) mouseWheelDown.addAndGet(-direction);
    }

    /** Value for a RETRO_DEVICE_KEYBOARD poll; unknown RETROK codes read as released. */
    protected final short pollKey(int retroKey) {
        return (retroKey >= 0 && retroKey < KEY_STATE_SIZE) ? (short) keyState.get(retroKey) : (short) 0;
    }

    /**
     * Value for a RETRO_DEVICE_MOUSE poll. X/Y are drained on read (the core
     * consumes the delta once per frame), wheel is a one-frame pulse per detent.
     */
    protected final short pollMouse(int id) {
        return switch (id) {
            case LibretroBridge.RETRO_DEVICE_ID_MOUSE_X -> (short) mouseDx.getAndSet(0);
            case LibretroBridge.RETRO_DEVICE_ID_MOUSE_Y -> (short) mouseDy.getAndSet(0);
            case LibretroBridge.RETRO_DEVICE_ID_MOUSE_WHEELUP ->
                    (short) (mouseWheelUp.getAndUpdate(v -> Math.max(0, v - 1)) > 0 ? 1 : 0);
            case LibretroBridge.RETRO_DEVICE_ID_MOUSE_WHEELDOWN ->
                    (short) (mouseWheelDown.getAndUpdate(v -> Math.max(0, v - 1)) > 0 ? 1 : 0);
            default -> (id >= 0 && id < 16) ? (short) mouseButtonState.get(id) : (short) 0;
        };
    }

    /** Maximum number of controller ports exposed to libretro cores. */
    public static final int MAX_PORTS = 2;

    public abstract void reset();

    public abstract byte[] serialize();

    public abstract boolean unserialize(byte[] data);

    public abstract byte[] getSaveRam();

    public abstract boolean setSaveRam(byte[] sram);

    /** {@code retro_serialize_size()} — 0 if unsupported. */
    public long getSerializeSize() { return 0; }

    public boolean supportsSaveStates() {
        return !isPcsx2Core() && getSerializeSize() > 0;
    }

    /** Up to dst.length interleaved stereo 16-bit samples; returns number of shorts. */
    public int readAudio(short[] dst) { return readAudio(dst, dst.length); }

    /** Same, but at most maxShorts samples per call (for lockstep A/V). */
    public int readAudio(short[] dst, int maxShorts) { return 0; }

    /** Interleaved stereo 16-bit samples in buffer (for lockstep A/V). */
    public int getAudioAvailable() { return 0; }

    /** PCM sample rate, Hz. */
    public double getAudioSampleRate() { return 48000.0; }

    /** Exact core FPS — for FrameSender pacing. */
    public double getTimingFps() { return 60.0; }

    /**
     * PCSX2 delivers all PCM in one batch at end of retro_run — audio and video
     * must be sent together. Other cores (PSP, PS1, …) stream audio.
     */
    public boolean prefersAvLockstep() { return false; }

    @Override
    public abstract void close() throws Exception;

    /** Path to the core binary the user requested, or {@code null} for fallback instances. */
    public Path getCorePath() {
        return corePath;
    }

    public boolean usesFrontendBatteryRam() {
        return CoreSavePolicy.usesFrontendBatteryRam(corePath);
    }

    public boolean isTouchCore() {
        if (corePath == null) return false;
        String n = corePath.getFileName().toString().toLowerCase();
        return n.contains("melonds") || n.contains("desmume") || n.contains("citra");
    }

    public boolean isPcsx2Core() {
        return corePath != null
                && corePath.getFileName().toString().toLowerCase().contains("pcsx2");
    }
}
