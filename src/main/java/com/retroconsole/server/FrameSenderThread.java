package com.retroconsole.server;

import com.retroconsole.emu.ThreadedEmulatorRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends libretro emulator frames over the network at a fixed rate,
 * independent of Minecraft's {@code ServerTickEvent} (which fires at
 * 20 Hz by default and is too coarse for interlaced PSX).
 *
 * <p>Without this thread, the player would only see ~20 fps regardless
 * of what the emulator produces, because {@code ServerConsoles.tick}
 * runs on the Minecraft server tick. With this thread, the frame send
 * rate is {@link #PERIOD_NS} = 60 Hz.
 *
 * <p>The thread reads from {@link ThreadedEmulatorRuntime#pollFrame(int[])}
 * just like the old tick path. It is started in
 * {@code ServerConsoles.startEmulator} and stopped in {@code stopEmulator}
 * or {@link #stopAll()}.
 *
 * <p>The thread resolves its {@link MinecraftServer} reference lazily on
 * each frame, so it survives restarts of the underlying server object
 * if that ever happens.
 */
public class FrameSenderThread extends Thread {
    private static final Logger LOGGER = LoggerFactory.getLogger("FrameSender-Thread");
    private static final long PERIOD_NS = 16_666_666L; // ~60 Hz

    private final BlockPos consolePos;
    private final ThreadedEmulatorRuntime threaded;
    /** Reusable buffer sized to the current geometry; reallocated when it changes. */
    private int[] buf = new int[0];
    private volatile boolean running = true;

    // DEBUG: per-frame logging — see what gets sent where.
    private long dbgStartNs = System.nanoTime();
    private long dbgFrameCount = 0;
    private long dbgBlackAllCount = 0;
    private long dbgLastLogNs = 0;

    FrameSenderThread(BlockPos consolePos, ThreadedEmulatorRuntime threaded) {
        super("retro-frame-sender-" + consolePos.toShortString());
        setDaemon(true);
        this.consolePos = consolePos;
        this.threaded = threaded;
    }

    @Override
    public void run() {
        try {
            long next = System.nanoTime();
            while (running) {
                next += PERIOD_NS;
                long sleepNs = next - System.nanoTime();
                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                } else if (sleepNs < -PERIOD_NS) {
                    // We lagged by more than one frame; resync.
                    next = System.nanoTime();
                }

                if (!running) break;

                boolean hasFrame = threaded.pollFrame(buf);
                if (!hasFrame) continue;

                int w = threaded.getCurrentWidth();
                int h = threaded.getCurrentHeight();
                if (w <= 0 || h <= 0) continue;
                int needed = w * h;
                if (buf.length != needed) {
                    buf = new int[needed];
                    // Try one more poll so the new buf has pixels for this frame.
                    threaded.pollFrame(buf);
                }

                // DEBUG: dump every frame about once a second, and always
                // mention "all-black" frames since that is what we are chasing.
                int[] debugBuf = buf;
                dbgFrameCount++;
                long now = System.nanoTime();
                long ageMs = (now - dbgStartNs) / 1_000_000L;
                int blackPixels = 0;
                int nonBlackPixels = 0;
                for (int p : debugBuf) {
                    if ((p & 0x00FFFFFF) == 0) blackPixels++;
                    else nonBlackPixels++;
                }
                boolean allBlack = (nonBlackPixels == 0);
                if (allBlack) dbgBlackAllCount++;
                if (now - dbgLastLogNs > 1_000_000_000L) {
                    LOGGER.info("DEBUG-FSENDER at +{}ms: sent frames={}, all-black frames={}, lastSize={}x{}, last-black/nonblack={}/{}",
                            ageMs, dbgFrameCount, dbgBlackAllCount, w, h, blackPixels, nonBlackPixels);
                    dbgFrameCount = 0;
                    dbgBlackAllCount = 0;
                    dbgLastLogNs = now;
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
                        if (player.blockPosition().distSqr(consolePos) < viewDist * viewDist) {
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

    void stopSender() {
        running = false;
        interrupt();
    }
}
