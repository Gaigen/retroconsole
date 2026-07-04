package com.retroconsole.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Windows stub for {@link LibretroCore}.
 *
 * <p>This is a deliberate starting point: the user wanted Linux code to stay focused on
 * Linux, and Windows support to start from a clean, minimal base. No JNA, no headless
 * GL, no Flycast patches — all of that lives in {@link LibretroCoreLinux} and has no
 * place in a Windows implementation that does not yet exist.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #loadGame(Path)} returns {@code false}.</li>
 *   <li>Frame sources stay at zero size.</li>
 *   <li>Inputs, save states, SRAM and reset are no-ops.</li>
 *   <li>The mod continues to load: blocks can be placed, the GUI can list cores,
 *       but the emulator thread will not start because {@code loadCoreAndGame}
 *       in {@code CoreManager} sees the {@code null} return.</li>
 * </ul>
 *
 * <p>To wire up real Windows support later, replace the bodies below with a JNA bridge
 * to {@code libretro.dll} / {@code headless_gl.dll} and (optionally) implement a
 * Windows headless GL backend. None of that should require touching
 * {@link LibretroCoreLinux}.
 */
public class LibretroCoreWindows extends LibretroCore {
    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroCoreWindows");

    private final String systemDir;
    private final String saveDir;

    public LibretroCoreWindows(Path corePath, String systemDir, String saveDir) {
        super(corePath);
        this.systemDir = systemDir;
        this.saveDir = saveDir;
        LOGGER.info("Windows libretro core stub created for {} (system={}, save={})",
                corePath, systemDir, saveDir);
    }

    @Override
    public boolean loadGame(Path romPath) {
        LOGGER.warn("loadGame({}) called but Windows emulator support is not implemented.",
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
