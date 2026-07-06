package com.retroconsole.emu;

import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.platform.BatterySaveManager;
import com.retroconsole.platform.PlayerPaths;
import com.retroconsole.platform.Pcsx2MemcardSync;
import com.retroconsole.platform.SaveStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Wraps a LibretroCore as a FrameSource for use with ThreadedEmulatorRuntime.
 * Also manages save state persistence.
 */
public class LibretroRuntime implements FrameSource, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("LibretroRuntime");

    private final LibretroCore core;
    private final Path romPath;
    private final PlayerPaths playerPaths;
    private int[] frameBuffer;
    private int width;
    private int height;

    public LibretroRuntime(LibretroCore core, Path romPath, PlayerPaths playerPaths) {
        this.core = core;
        this.romPath = romPath;
        this.playerPaths = playerPaths != null ? playerPaths : PlayerPaths.shared();
        this.width = core.getWidth();
        this.height = core.getHeight();
        this.frameBuffer = new int[Math.max(width, 1) * Math.max(height, 1)];
    }

    public LibretroRuntime(LibretroCore core, Path romPath) {
        this(core, romPath, PlayerPaths.shared());
    }

    @Override
    public void runFrame() {
        core.runFrame();

        // Copy frame from core's internal buffer to our buffer
        core.pollFrame(frameBuffer);

        // Check if resolution changed
        int newW = core.getWidth();
        int newH = core.getHeight();
        if (newW != width || newH != height) {
            LOGGER.info("Resolution changed: {}x{} -> {}x{}", width, height, newW, newH);
            width = newW;
            height = newH;
            // Resize our frame buffer
            int needed = Math.max(width * height, 1);
            if (frameBuffer.length != needed) {
                frameBuffer = new int[needed];
            }
        }
    }

    @Override
    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    @Override
    public int getWidth() { return width; }

    @Override
    public int getHeight() { return height; }

    @Override
    public double getTimingFps() { return core.getTimingFps(); }

    /**
     * Called by ThreadedEmulatorRuntime indirectly — ServerConsoles calls pollFrame.
     * This is the bridge: pollFrame copies from LibretroCore's internal buffer
     * into our frameBuffer.
     */
    public boolean pollFrame(int[] dst) {
        return core.pollFrame(dst);
    }

    public int readAudio(short[] dst) { return core.readAudio(dst); }

    public int readAudio(short[] dst, int maxShorts) { return core.readAudio(dst, maxShorts); }

    public double getAudioSampleRate() { return core.getAudioSampleRate(); }

    public LibretroCore getCore() { return core; }

    public Path getRomPath() { return romPath; }

    public PlayerPaths getPlayerPaths() { return playerPaths; }

    public boolean supportsSaveStates() {
        return SaveStateManager.supportsSaveStates(core);
    }

    public boolean saveBattery() {
        return BatterySaveManager.saveFromCore(core, romPath, playerPaths);
    }

    public boolean saveState(int slot) {
        return SaveStateManager.save(core, romPath, playerPaths, slot);
    }

    public boolean loadState(int slot) {
        return SaveStateManager.load(core, romPath, playerPaths, slot);
    }

    // --- Input ---

    public void setButton(int buttonId, boolean pressed) {
        core.setButton(buttonId, pressed);
    }

    public void setAnalog(int stick, int axis, short value) {
        core.setAnalog(stick, axis, value);
    }

    public void reset() {
        core.reset();
    }

    // --- Save management ---

    public byte[] getSaveState() {
        return core.serialize();
    }

    public boolean loadSaveState(byte[] data) {
        return core.unserialize(data);
    }

    public byte[] getSaveRam() {
        return core.getSaveRam();
    }

    public boolean setSaveRam(byte[] data) {
        return core.setSaveRam(data);
    }

    @Override
    public void close() {
        saveBattery();

        try {
            core.close();
        } catch (Exception e) {
            LOGGER.warn("Failed to close core", e);
        }

        if (core.isPcsx2Core()) {
            Pcsx2MemcardSync.export(playerPaths);
        }
    }
}
