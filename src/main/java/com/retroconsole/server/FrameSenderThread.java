package com.retroconsole.server;

import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import com.retroconsole.network.RetroAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
    private static final long DEFAULT_PERIOD_NS = 16_666_666L;
    private static final double AUDIO_RADIUS = 32.0;

    private final BlockPos consolePos;
    private final ThreadedEmulatorRuntime threaded;
    private final LibretroCore core;
    private int[] buf = new int[0];
    private final short[] audioChunk = new short[9600];
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
            while (running) {
                double fps = core.getTimingFps();
                long periodNs = fps > 1.0 ? (long) (1_000_000_000L / fps) : DEFAULT_PERIOD_NS;
                next += periodNs;
                long sleepNs = next - System.nanoTime();
                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } else if (sleepNs < -periodNs) {
                    next = System.nanoTime();
                }

                if (!running) break;

                sendAudioIfReady();

                boolean hasFrame = threaded.pollFrame(buf);
                if (!hasFrame) continue;

                int w = threaded.getCurrentWidth();
                int h = threaded.getCurrentHeight();
                if (w <= 0 || h <= 0) continue;
                int needed = w * h;
                if (buf.length != needed) {
                    buf = new int[needed];
                    threaded.pollFrame(buf);
                }

                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) continue;

                int[] out = buf;
                for (int i = 0; i < out.length; i++) {
                    out[i] = 0xFF000000 | (out[i] & 0x00FFFFFF);
                }
                int viewDist = ServerConsoles.viewDistance();
                for (ServerLevel level : server.getAllLevels()) {
                    for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                        if (player.level() != level) continue;
                        if (player.blockPosition().distSqr(consolePos) < (long) viewDist * viewDist) {
                            ServerTickHandler.sendFrameToPlayer(player, consolePos, out, w, h);
                        }
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOGGER.error("FrameSenderThread crashed for {}", consolePos, t);
        }
    }

    private void sendAudioIfReady() {
        int n = core.readAudio(audioChunk);
        if (n <= 0) return;

        byte[] mono = new byte[n];
        int mi = 0;
        for (int i = 0; i < n; i += 2) {
            int m = (audioChunk[i] + audioChunk[i + 1]) >> 1;
            mono[mi++] = (byte) m;
            mono[mi++] = (byte) (m >> 8);
        }
        int sr = (int) Math.round(core.getAudioSampleRate());
        RetroAudioPayload packet = new RetroAudioPayload(consolePos, sr, mono);

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        double radiusSq = AUDIO_RADIUS * AUDIO_RADIUS;
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (player.level() != level) continue;
                if (player.blockPosition().distSqr(consolePos) < radiusSq) {
                    ServerTickHandler.sendAudioToPlayer(player, packet);
                }
            }
        }
    }

    void stopSender() {
        LOGGER.info("stopSender(): terminating frame sender for {}", consolePos);
        running = false;
        interrupt();
    }
}
