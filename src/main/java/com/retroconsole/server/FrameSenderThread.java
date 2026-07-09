package com.retroconsole.server;

import com.retroconsole.emu.LibretroRuntime;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import com.retroconsole.network.RetroAudioPayload;
import com.retroconsole.network.RetroFramePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Sends libretro emulator frames and audio over the network at the core's
 * native frame rate, independent of Minecraft's {@code ServerTickEvent}.
 *
 * <p>IMPORTANT: video and audio are sent ONLY to real recipients: players who
 * opened the console screen ({@link ServerConsoles#viewers}) and players near the
 * TV block. With no recipients, frames are not polled at all (the emulator keeps
 * ticking, but we spend no readback, network, or client allocations).
 *
 * <p>OPTIMIZATION: each frame is compressed once and one packet is sent to all
 * (same approach as RetroAudioPayload). World viewers (not in TvScreen) get a
 * downscaled copy — the in-world TV block does not need full resolution.
 */
public class FrameSenderThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger("FrameSender-Thread");
    private static final long BATTERY_AUTOSAVE_NS = 30L * 1_000_000_000L;

    /**
     * Frame width for players who see the in-world screen but have NOT opened TvScreen.
     * Full resolution (e.g. 1920×1440 on PSP/PS2 HW render) is unnecessary: saves bandwidth,
     * server deflate work, and client allocations, and keeps custom payloads under ~1 MiB.
     * Side effect: entering/leaving TvScreen changes resolution and the client texture is
     * recreated — normal resize path in ClientConsoles.
     */
    private static final int WORLD_MAX_WIDTH = 480;

    private final BlockPos consolePos;
    private final ThreadedEmulatorRuntime threaded;
    private final LibretroRuntime runtime;

    private int[] buf;
    private int[] scaledBuf = new int[0];
    private short[] audioChunk = new short[4096];
    private volatile boolean running = true;
    private long lastBatterySaveNs = System.nanoTime();

    FrameSenderThread(BlockPos consolePos, ThreadedEmulatorRuntime threaded, LibretroRuntime runtime) {
        super("retro-frame-sender-" + consolePos.toShortString());
        setDaemon(true);
        this.consolePos = consolePos;
        this.threaded = threaded;
        this.runtime = runtime;
        // Size buffer up front: pollFrame with an empty array would return false
        // and the resize logic would never run.
        int w = Math.max(1, runtime.getWidth());
        int h = Math.max(1, runtime.getHeight());
        this.buf = new int[w * h];
    }

    @Override
    public void run() {
        try {
            long next = System.nanoTime();
            boolean lockstep = runtime.getCore().prefersAvLockstep();
            while (running) {
                if (lockstep) {
                    runLockstepFrame();
                    continue;
                }

                double fps = Math.max(1.0, runtime.getCore().getTimingFps());
                long periodNs = (long) (1_000_000_000L / fps);
                long deadline = next + periodNs;
                sleepUntil(deadline);
                if (!running) break;
                next = deadline;

                maybeAutosaveBattery();

                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (!canSendToServer(server)) continue;

                List<ServerPlayer> players = snapshotPlayers(server);

                // Audio: drain the ring every tick (otherwise it overflows), but send only to listeners.
                int n = runtime.readAudio(audioChunk);
                List<ServerPlayer> audioTo = recipients(players, ServerConsoles.audioDistance());
                if (n > 0 && !audioTo.isEmpty()) {
                    dispatchAudio(server, n, audioTo);
                }

                // Video: no recipients — do not even poll a frame.
                // newFrame stays true and drainHwFrame skips readback
                // (see skip in LibretroCoreWindows.drainHwFrame).
                List<ServerPlayer> videoTo = recipients(players, ServerConsoles.videoDistance());
                if (videoTo.isEmpty()) continue;

                if (!pollFrameResized()) continue;

                int w = threaded.getCurrentWidth();
                int h = threaded.getCurrentHeight();
                if (w <= 0 || h <= 0) continue;

                sendVideoFrame(server, w, h, videoTo);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOGGER.error("FrameSenderThread crashed for {}", consolePos, t);
        }
    }

    /** PCSX2: wait for a frame from the emulator, drain all PCM for this retro_run. */
    private void runLockstepFrame() throws InterruptedException {
        while (running && !threaded.hasNewFrame()) {
            Thread.sleep(1, 0);
        }
        if (!running) return;

        maybeAutosaveBattery();

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (!canSendToServer(server)) {
            // Must clear the frame flag and drain the audio ring, or the outer loop becomes a busy-loop.
            threaded.pollFrame(buf);
            drainAudioRing();
            return;
        }

        List<ServerPlayer> players = snapshotPlayers(server);
        List<ServerPlayer> videoTo = recipients(players, ServerConsoles.videoDistance());
        List<ServerPlayer> audioTo = recipients(players, ServerConsoles.audioDistance());

        // Drain this retro_run's audio always (the ring is not elastic).
        int avail = runtime.getAudioAvailable();
        if (avail > audioChunk.length) {
            audioChunk = new short[avail];
        }
        int n = avail > 0 ? runtime.readAudio(audioChunk, avail) : 0;

        if (videoTo.isEmpty()) {
            // Nobody needs the frame: clear the flag and exit.
            threaded.pollFrame(buf);
            if (n > 0 && !audioTo.isEmpty()) dispatchAudio(server, n, audioTo);
            return;
        }

        if (!pollFrameResized()) return;

        int w = threaded.getCurrentWidth();
        int h = threaded.getCurrentHeight();
        if (w <= 0 || h <= 0) return;

        if (n > 0 && !audioTo.isEmpty()) dispatchAudio(server, n, audioTo);
        sendVideoFrame(server, w, h, videoTo);
    }

    /**
     * Polls a frame; on resolution change resizes the buffer and repolls.
     * @return true if {@link #buf} holds a valid frame.
     */
    private boolean pollFrameResized() {
        if (!threaded.pollFrame(buf)) return false;
        int w = threaded.getCurrentWidth();
        int h = threaded.getCurrentHeight();
        if (w <= 0 || h <= 0) return false;
        int needed = w * h;
        if (buf.length != needed) {
            buf = new int[needed];
            return threaded.pollFrame(buf);
        }
        return true;
    }

    /**
     * Players who need the stream: watching TvScreen or standing within range.
     * Previously duplicated as videoRecipients/audioRecipients.
     */
    private List<ServerPlayer> recipients(List<ServerPlayer> players, int distance) {
        Set<UUID> viewers = ServerConsoles.viewers(consolePos);
        long rSq = (long) distance * distance;
        List<ServerPlayer> out = new ArrayList<>(2);
        for (ServerPlayer p : players) {
            if (p.hasDisconnected()) continue;
            if (viewers.contains(p.getUUID())
                    || p.blockPosition().distSqr(consolePos) < rSq) {
                out.add(p);
            }
        }
        return out;
    }

    /** Interleaved stereo 16-bit LE — no downmix to mono (fewer artifacts). */
    private void dispatchAudio(MinecraftServer server, int nShorts, List<ServerPlayer> recipients) {
        if (nShorts <= 0 || !canSendToServer(server)) return;
        byte[] pcm = new byte[nShorts * 2];
        for (int i = 0; i < nShorts; i++) {
            short s = audioChunk[i];
            int b = i * 2;
            pcm[b] = (byte) s;
            pcm[b + 1] = (byte) (s >> 8);
        }
        int sr = (int) Math.round(runtime.getAudioSampleRate());
        RetroAudioPayload packet = new RetroAudioPayload(consolePos, sr, pcm);
        for (ServerPlayer player : recipients) {
            if (!canSendToServer(server)) return;
            if (player.hasDisconnected()) continue;
            ServerTickHandler.sendAudioToPlayer(player, packet);
        }
    }

    /**
     * OPTIMIZATION (was TODO(perf)): packet is built and compressed once for all
     * recipients. RetroFramePacket.create (RGB conversion + full-frame deflate)
     * used to run in a per-player loop.
     *
     * <p>TvScreen viewers get full resolution; others (in-world TV block) are
     * downscaled to WORLD_MAX_WIDTH.
     */
    private void sendVideoFrame(MinecraftServer server, int w, int h, List<ServerPlayer> recipients) {
        if (!canSendToServer(server)) return;

        Set<UUID> viewers = ServerConsoles.viewers(consolePos);
        List<ServerPlayer> fullRes = new ArrayList<>(recipients.size());
        List<ServerPlayer> worldRes = new ArrayList<>(recipients.size());
        for (ServerPlayer p : recipients) {
            if (p.hasDisconnected()) continue;
            if (viewers.contains(p.getUUID())) {
                fullRes.add(p);
            } else {
                worldRes.add(p);
            }
        }

        RetroFramePacket fullPacket = null;
        if (!fullRes.isEmpty()) {
            fullPacket = RetroFramePacket.create(consolePos, buf, w, h);
            for (ServerPlayer player : fullRes) {
                if (!canSendToServer(server)) return;
                ServerTickHandler.sendFrameToPlayer(player, fullPacket);
            }
        }

        if (!worldRes.isEmpty()) {
            RetroFramePacket worldPacket;
            if (w > WORLD_MAX_WIDTH) {
                int sw = WORLD_MAX_WIDTH;
                int sh = Math.max(1, Math.round((float) h * sw / w));
                downscale(buf, w, h, sw, sh);
                worldPacket = RetroFramePacket.create(consolePos, scaledBuf, sw, sh);
            } else {
                // Frame is already small — reuse the packet we already built.
                worldPacket = fullPacket != null
                        ? fullPacket
                        : RetroFramePacket.create(consolePos, buf, w, h);
            }
            for (ServerPlayer player : worldRes) {
                if (!canSendToServer(server)) return;
                ServerTickHandler.sendFrameToPlayer(player, worldPacket);
            }
        }
    }

    /** Nearest neighbor — cheap and sufficient for the in-world screen block. */
    private void downscale(int[] src, int w, int h, int sw, int sh) {
        if (scaledBuf.length < sw * sh) {
            scaledBuf = new int[sw * sh];
        }
        for (int y = 0; y < sh; y++) {
            int sy = (int) ((long) y * h / sh);
            int rowSrc = sy * w;
            int rowDst = y * sw;
            for (int x = 0; x < sw; x++) {
                scaledBuf[rowDst + x] = src[rowSrc + (int) ((long) x * w / sw)];
            }
        }
    }

    /** Drain the audio ring without sending (prevents overflow). */
    private void drainAudioRing() {
        int avail = runtime.getAudioAvailable();
        if (avail <= 0) return;
        if (avail > audioChunk.length) audioChunk = new short[avail];
        runtime.readAudio(audioChunk, avail);
    }

    private void maybeAutosaveBattery() {
        long now = System.nanoTime();
        if (now - lastBatterySaveNs < BATTERY_AUTOSAVE_NS) return;
        lastBatterySaveNs = now;
        runtime.saveBattery();
    }

    private static void sleepUntil(long deadline) throws InterruptedException {
        long sleepNs = deadline - System.nanoTime();
        if (sleepNs > 0) {
            Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
        }
    }

    private boolean canSendToServer(MinecraftServer server) {
        return running && server != null && server.isRunning();
    }

    private static List<ServerPlayer> snapshotPlayers(MinecraftServer server) {
        try {
            return new ArrayList<>(server.getPlayerList().getPlayers());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    void stopSender() {
        running = false;
        interrupt();
    }

    void stopAndJoin() {
        stopSender();
        try {
            join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}