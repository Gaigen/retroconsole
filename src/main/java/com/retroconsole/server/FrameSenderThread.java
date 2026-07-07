package com.retroconsole.server;

import com.retroconsole.emu.LibretroRuntime;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import com.retroconsole.network.RetroAudioPayload;
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
 * <p>ВАЖНО: видео и аудио отправляются ТОЛЬКО реальным получателям:
 * игрокам, открывшим экран консоли ({@link ServerConsoles#viewers}), и
 * игрокам рядом с блоком ТВ. Если получателей нет — кадр не поллится
 * вообще (эмулятор продолжает тикать, но мы не тратим ни readback, ни
 * сеть, ни аллокации на клиенте).
 */
public class FrameSenderThread extends Thread {

    private static final Logger LOGGER = LoggerFactory.getLogger("FrameSender-Thread");
    private static final long BATTERY_AUTOSAVE_NS = 30L * 1_000_000_000L;

    private final BlockPos consolePos;
    private final ThreadedEmulatorRuntime threaded;
    private final LibretroRuntime runtime;

    private int[] buf;
    private short[] audioChunk = new short[4096];
    private volatile boolean running = true;
    private long lastBatterySaveNs = System.nanoTime();

    FrameSenderThread(BlockPos consolePos, ThreadedEmulatorRuntime threaded, LibretroRuntime runtime) {
        super("retro-frame-sender-" + consolePos.toShortString());
        setDaemon(true);
        this.consolePos = consolePos;
        this.threaded = threaded;
        this.runtime = runtime;
        // Сразу правильный размер: pollFrame с пустым массивом вернул бы false,
        // и логика ресайза никогда бы не сработала.
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

                // --- АУДИО: ринг сливаем каждый тик (иначе переполнится),
                // но отправляем только тем, кто может слышать.
                int n = runtime.readAudio(audioChunk);
                List<ServerPlayer> audioTo = audioRecipients(players);
                if (n > 0 && !audioTo.isEmpty()) {
                    dispatchAudio(server, n, audioTo);
                }

                // --- ВИДЕО: нет получателей — кадр даже не поллим.
                // newFrame остаётся true, и drainHwFrame перестаёт делать
                // readback (см. скип в LibretroCoreWindows.drainHwFrame).
                List<ServerPlayer> videoTo = videoRecipients(players);
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

    /** PCSX2: ждём кадр от эмулятора, сливаем весь PCM этого retro_run. */
    private void runLockstepFrame() throws InterruptedException {
        while (running && !threaded.hasNewFrame()) {
            Thread.sleep(1, 0);
        }
        if (!running) return;

        maybeAutosaveBattery();

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (!canSendToServer(server)) {
            // ОБЯЗАТЕЛЬНО сбрасываем флаг кадра и сливаем аудио-ринг,
            // иначе внешний while превратится в busy-loop.
            threaded.pollFrame(buf);
            drainAudioRing();
            return;
        }

        List<ServerPlayer> players = snapshotPlayers(server);
        List<ServerPlayer> videoTo = videoRecipients(players);
        List<ServerPlayer> audioTo = audioRecipients(players);

        // Аудио этого retro_run сливаем всегда (ринг не резиновый).
        int avail = runtime.getAudioAvailable();
        if (avail > audioChunk.length) {
            audioChunk = new short[avail];
        }
        int n = avail > 0 ? runtime.readAudio(audioChunk, avail) : 0;

        if (videoTo.isEmpty()) {
            // Кадр никому не нужен: сбрасываем флаг и выходим.
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
     * Поллит кадр, при смене разрешения ресайзит буфер и репуллит.
     * @return true если в {@link #buf} лежит валидный кадр.
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

    // ------------------------------------------------------------------
    // Получатели
    // ------------------------------------------------------------------

    /** Игроки, которым нужен кадр: смотрят экран или стоят возле ТВ. */
    private List<ServerPlayer> videoRecipients(List<ServerPlayer> players) {
        Set<UUID> viewers = ServerConsoles.viewers(consolePos);
        long rSq = (long) ServerConsoles.videoDistance() * ServerConsoles.videoDistance();
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

    /** Игроки, которым нужен звук: смотрят экран или стоят в радиусе слышимости. */
    private List<ServerPlayer> audioRecipients(List<ServerPlayer> players) {
        Set<UUID> viewers = ServerConsoles.viewers(consolePos);
        long rSq = (long) ServerConsoles.audioDistance() * ServerConsoles.audioDistance();
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

    // ------------------------------------------------------------------
    // Отправка
    // ------------------------------------------------------------------

    /** Interleaved stereo 16-bit LE — без downmix в mono (меньше артефактов). */
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

    private void sendVideoFrame(MinecraftServer server, int w, int h, List<ServerPlayer> recipients) {
        if (!canSendToServer(server)) return;
        // Альфа-проход убран: оба продюсера кадра (drainHwFrame и софт-путь
        // videoCb) уже пишут 0xFF в альфу.
        //
        // TODO(perf): если ServerTickHandler.sendFrameToPlayer сжимает кадр
        // внутри — вынести компрессию сюда и собирать пакет ОДИН раз на всех
        // получателей (как сделано с RetroAudioPayload выше).
        for (ServerPlayer player : recipients) {
            if (!canSendToServer(server)) return;
            if (player.hasDisconnected()) continue;
            ServerTickHandler.sendFrameToPlayer(player, consolePos, buf, w, h);
        }
    }

    /** Слить аудио-ринг без отправки (чтобы не переполнялся). */
    private void drainAudioRing() {
        int avail = runtime.getAudioAvailable();
        if (avail <= 0) return;
        if (avail > audioChunk.length) audioChunk = new short[avail];
        runtime.readAudio(audioChunk, avail);
    }

    // ------------------------------------------------------------------
    // Служебное
    // ------------------------------------------------------------------

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