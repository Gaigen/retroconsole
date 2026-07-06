package com.retroconsole.server;

import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import com.retroconsole.network.RetroAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends libretro emulator frames and audio over the network at the core's native
 * frame rate, independent of Minecraft's {@code ServerTickEvent}.
 */
public class FrameSenderThread extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger("FrameSender-Thread");

    private final BlockPos consolePos;
    private final ThreadedEmulatorRuntime threaded;
    private final LibretroCore core;
    private int[] buf = new int[0];
    private final short[] audioChunk = new short[4096];
    private volatile boolean running = true;

    FrameSenderThread(BlockPos consolePos, ThreadedEmulatorRuntime threaded, LibretroCore core) {
        super("retro-frame-sender-" + consolePos.toShortString());
        setDaemon(true);
        this.consolePos = consolePos;
        this.threaded = threaded;
        this.core = core;
    }

    @Override
    public void run() {
        try {
            long next = System.nanoTime();
            boolean lockstep = core.prefersAvLockstep();
            while (running) {
                double fps = Math.max(1.0, core.getTimingFps());
                long periodNs = (long) (1_000_000_000L / fps);
                long deadline = next + periodNs;

                if (lockstep) {
                    while (running && System.nanoTime() < deadline && !threaded.hasNewFrame()) {
                        Thread.sleep(1, 0);
                    }
                } else {
                    sleepUntil(deadline);
                }
                if (!running) break;

                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (!lockstep && canSendToServer(server)) {
                    sendStreamingAudio(server);
                }

                boolean hasFrame = threaded.pollFrame(buf);
                if (!hasFrame) {
                    next = deadline;
                    continue;
                }

                int w = threaded.getCurrentWidth();
                int h = threaded.getCurrentHeight();
                if (w <= 0 || h <= 0) {
                    next = deadline;
                    continue;
                }
                int needed = w * h;
                if (buf.length != needed) {
                    buf = new int[needed];
                    if (!threaded.pollFrame(buf)) {
                        next = deadline;
                        continue;
                    }
                }

                if (!canSendToServer(server)) {
                    next = deadline;
                    continue;
                }

                if (lockstep) {
                    sendLockstepAudio(fps, server);
                }
                sendVideoFrame(server, w, h);

                next = deadline;
                if (lockstep) {
                    sleepUntil(deadline);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOGGER.error("FrameSenderThread crashed for {}", consolePos, t);
        }
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

    private static java.util.List<ServerPlayer> snapshotPlayers(MinecraftServer server) {
        try {
            return new java.util.ArrayList<>(server.getPlayerList().getPlayers());
        } catch (RuntimeException e) {
            return java.util.List.of();
        }
    }

    /** PSP/PS1/… — звук каждый тик, независимо от видео-кадра. */
    private void sendStreamingAudio(MinecraftServer server) {
        int n = core.readAudio(audioChunk);
        dispatchAudio(server, n);
    }

    /** PCSX2 — один чанк PCM на кадр, в lockstep с видео. */
    private void sendLockstepAudio(double fps, MinecraftServer server) {
        int maxShorts = (int) Math.ceil(core.getAudioSampleRate() / fps) * 2 + 64;
        maxShorts = Math.min(maxShorts, audioChunk.length);
        int n = core.readAudio(audioChunk, maxShorts);
        dispatchAudio(server, n);
    }

    private void dispatchAudio(MinecraftServer server, int n) {
        if (n <= 0 || !canSendToServer(server)) return;

        byte[] mono = new byte[n];
        int mi = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int m = (audioChunk[i] + audioChunk[i + 1]) >> 1;
            mono[mi++] = (byte) m;
            mono[mi++] = (byte) (m >> 8);
        }
        if (mi == 0) return;

        byte[] pcm = (mi == mono.length) ? mono : java.util.Arrays.copyOf(mono, mi);
        int sr = (int) Math.round(core.getAudioSampleRate());
        RetroAudioPayload packet = new RetroAudioPayload(consolePos, sr, pcm);

        int audioRadius = ServerConsoles.viewDistance();
        long radiusSq = (long) audioRadius * audioRadius;
        for (ServerPlayer player : snapshotPlayers(server)) {
            if (!canSendToServer(server)) return;
            if (player.hasDisconnected()) continue;
            if (player.blockPosition().distSqr(consolePos) < radiusSq) {
                ServerTickHandler.sendAudioToPlayer(player, packet);
            }
        }
    }

    private void sendVideoFrame(MinecraftServer server, int w, int h) {
        if (!canSendToServer(server)) return;
        int[] out = buf;
        for (int i = 0; i < out.length; i++) {
            out[i] = 0xFF000000 | (out[i] & 0x00FFFFFF);
        }
        int viewDist = ServerConsoles.viewDistance();
        long radiusSq = (long) viewDist * viewDist;
        for (ServerPlayer player : snapshotPlayers(server)) {
            if (!canSendToServer(server)) return;
            if (player.hasDisconnected()) continue;
            if (player.blockPosition().distSqr(consolePos) < radiusSq) {
                ServerTickHandler.sendFrameToPlayer(player, consolePos, out, w, h);
            }
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
