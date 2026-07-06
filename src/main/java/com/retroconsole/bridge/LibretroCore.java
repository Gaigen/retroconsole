package com.retroconsole.bridge;

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
        @Override public void setAnalog(int stick, int axis, short value) { }
        @Override public void reset() { }
        @Override public byte[] serialize() { return null; }
        @Override public boolean unserialize(byte[] data) { return false; }
        @Override public byte[] getSaveRam() { return null; }
        @Override public void setSaveRam(byte[] sram) { }
        @Override public int readAudio(short[] dst) { return 0; }
        @Override public double getAudioSampleRate() { return 48000.0; }
        @Override public double getTimingFps() { return 60.0; }
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

    public abstract void setButton(int buttonId, boolean pressed);

    public abstract void setAnalog(int stick, int axis, short value);

    public abstract void reset();

    public abstract byte[] serialize();

    public abstract boolean unserialize(byte[] data);

    public abstract byte[] getSaveRam();

    public abstract void setSaveRam(byte[] sram);

    /** До dst.length сэмплов interleaved-стерео 16-bit; возвращает число short'ов. */
    public int readAudio(short[] dst) { return 0; }

    /** Частота PCM, Гц. */
    public double getAudioSampleRate() { return 48000.0; }

    /** Точный FPS ядра — для пейсинга FrameSender. */
    public double getTimingFps() { return 60.0; }

    @Override
    public abstract void close() throws Exception;

    /** Path to the core binary the user requested, or {@code null} for fallback instances. */
    public Path getCorePath() {
        return corePath;
    }
}
