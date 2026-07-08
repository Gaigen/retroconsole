package com.retroconsole.server;

import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.emu.CoreManager;
import com.retroconsole.emu.LibretroRuntime;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import com.retroconsole.network.RetroStopConsolePacket;
import com.retroconsole.platform.PlayerPaths;
import com.retroconsole.platform.RetroConsolePaths;
import com.retroconsole.platform.SaveStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ServerConsoles {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Server");

    /** Радиус для служебных уведомлений (stop-пакет — дёшево, можно широко). */
    private static final int NOTIFY_DISTANCE = 256;
    /** Радиус, в котором игрок видит экран ТВ в мире и получает видеокадры. */
    private static final int VIDEO_DISTANCE = 48;
    /** Радиус слышимости консоли. */
    private static final int AUDIO_DISTANCE = 32;

    // ConcurrentHashMap: мутации идут с server thread, а читают их
    // FrameSender-потоки — обычный HashMap здесь был бы гонкой.
    private static final ConcurrentHashMap<BlockPos, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Set<UUID>> VIEWERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, FrameSenderThread> FRAME_SENDERS = new ConcurrentHashMap<>();

    private static CoreManager coreManager;

    /** Фоновый shutdown ядра — не блокирует server thread при ломании блока. */
    private static final java.util.concurrent.ExecutorService SHUTDOWN_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "retro-console-shutdown");
                t.setDaemon(true);
                return t;
            });

    private record Entry(
            LibretroRuntime runtime,
            ThreadedEmulatorRuntime threaded,
            int[] buf,
            String coreName,
            String romId,
            UUID ownerId
    ) {}

    public static void init() {
        // Directories are created lazily by RetroConsolePaths on first read;
        // we just resolve them through the same source of truth here.
        Path cores = RetroConsolePaths.coresDir();
        Path system = RetroConsolePaths.systemDir();
        Path save = RetroConsolePaths.saveDir();
        coreManager = new CoreManager(cores, system, save);
        coreManager.discoverCores();
        RetroConsolePaths.logPathsSummary();
        LOGGER.info("CoreManager initialized. discovered {} cores", coreManager.getCores().size());
        if (coreManager.getCores().isEmpty()) {
            LOGGER.warn("No libretro cores found. Place .dll / .so / .dylib cores in: {}",
                    cores.toAbsolutePath().normalize());
        }
    }

    public static void startEmulator(BlockPos pos, String coreName, String romId, UUID ownerId) {
        startEmulator(pos, coreName, romId, ownerId, false);
    }

    public static void startEmulator(BlockPos pos, String coreName, String romId, UUID ownerId, boolean loadAuto) {
        pos = pos.immutable();
        Entry existing = ENTRIES.get(pos);
        if (existing != null) {
            if (existing.romId().equals(romId) && existing.coreName().equals(coreName)
                    && Objects.equals(existing.ownerId(), ownerId)) {
                return;
            }
            stopEmulator(pos);
        }
        if (coreManager == null) init();

        var coreInfo = coreManager.findCore(coreName);
        if (coreInfo == null) { LOGGER.error("Core not found: {}", coreName); return; }

        Path romPath = RetroConsolePaths.romsDir().resolve(romId).normalize();
        if (!romPath.toFile().exists()) { LOGGER.error("ROM not found: {}", romPath); return; }

        PlayerPaths playerPaths = ownerId != null
                ? PlayerPaths.forPlayer(ownerId)
                : PlayerPaths.shared();

        LibretroRuntime runtime = coreManager.loadCoreAndGame(coreInfo.path(), romPath, playerPaths);
        if (runtime == null) { LOGGER.error("Failed to load core {} with ROM {}", coreName, romId); return; }

        if (loadAuto) {
            runtime.runFrame();
            runtime.runFrame();
            boolean loaded = SaveStateManager.loadAutoOrSlot(
                    runtime.getCore(), romId, romPath, playerPaths);
            LOGGER.info("Continue load for {} -> {}", romId, loaded);
        }

        int w = Math.max(runtime.getWidth(), 1);
        int h = Math.max(runtime.getHeight(), 1);
        int[] buf = new int[w * h];

        ThreadedEmulatorRuntime threaded = new ThreadedEmulatorRuntime(runtime, w, h);
        threaded.start();

        // FrameSenderThread runs at ~60 Hz independent of Minecraft's
        // 20 Hz server tick — otherwise PS1 (and any interlaced core) shows
        // severe flicker because the client only sees one frame per tick.
        FrameSenderThread sender = new FrameSenderThread(pos, threaded, runtime);
        sender.start();

        ENTRIES.put(pos, new Entry(runtime, threaded, buf, coreName, romId, ownerId));
        FRAME_SENDERS.put(pos, sender);
        LOGGER.info("Started {} emulator at {} ({}x{}, owner={}, loadAuto={})",
                coreName, pos, w, h, ownerId, loadAuto);
    }

    public static void startEmulator(BlockPos pos, String coreName, String romId) {
        startEmulator(pos, coreName, romId, null);
    }

    public static void stopEmulator(BlockPos pos) {
        pos = pos.immutable();
        Entry e = ENTRIES.remove(pos);
        FrameSenderThread sender = FRAME_SENDERS.remove(pos);
        if (sender != null) sender.stopAndJoin();
        if (e != null) {
            LOGGER.info("stopEmulator({}): core={}, rom={}", pos, e.coreName(), e.romId());
            e.threaded().stop();
            boolean saved = SaveStateManager.saveAuto(
                    e.runtime().getCore(), e.romId(), e.runtime().getPlayerPaths());
            LOGGER.info("Auto save on stop {} -> {}", e.romId(), saved);
            scheduleClose(e.runtime(), pos);
        }
        notifyConsoleStopped(pos);
        VIEWERS.remove(pos);
    }

    private static void scheduleClose(LibretroRuntime runtime, BlockPos pos) {
        SHUTDOWN_EXECUTOR.submit(() -> {
            try {
                runtime.close();
                LOGGER.info("Core shutdown finished for {}", pos);
            } catch (Exception ex) {
                LOGGER.warn("Core shutdown failed for {}: {}", pos, ex.getMessage());
            }
        });
    }

    private static void awaitShutdown(long timeoutSec) {
        try {
            SHUTDOWN_EXECUTOR.submit(() -> null).get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.warn("Core shutdown still running after {}s", timeoutSec);
        } catch (Exception e) {
            LOGGER.debug("Shutdown wait: {}", e.getMessage());
        }
    }

    private static void notifyConsoleStopped(BlockPos pos) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isRunning()) return;
        RetroStopConsolePacket packet = new RetroStopConsolePacket(pos);
        long radiusSq = (long) NOTIFY_DISTANCE * NOTIFY_DISTANCE;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (player.hasDisconnected()) continue;
            if (player.blockPosition().distSqr(pos) < radiusSq) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    public static void tick(ServerLevel level) {
        // Frame sending moved off the Minecraft server tick — see
        // FrameSenderThread. Tick is kept as a registered no-op so the
        // existing @SubscribeEvent wiring stays valid and easy to extend.
    }

    public static LibretroCore getCore(BlockPos pos) {
        Entry e = ENTRIES.get(pos.immutable());
        return e != null ? e.runtime().getCore() : null;
    }

    public static void handleInput(BlockPos pos, int buttonId, boolean pressed) {
        Entry e = ENTRIES.get(pos.immutable());
        if (e != null) e.runtime().setButton(buttonId, pressed);
    }

    public static void handleAnalog(BlockPos pos, int stick, int axis, short value) {
        Entry e = ENTRIES.get(pos.immutable());
        if (e != null) e.runtime().setAnalog(stick, axis, value);
    }

    public static void handleSaveState(BlockPos pos, int slot, boolean save, boolean auto) {
        Entry e = ENTRIES.get(pos.immutable());
        if (e == null) return;
        if (auto) {
            if (!save) return;
            boolean ok = SaveStateManager.saveAuto(
                    e.runtime().getCore(), e.romId(), e.runtime().getPlayerPaths());
            LOGGER.info("Auto save state @ {} -> {}", pos, ok);
            return;
        }
        if (slot < 0 || slot > SaveStateManager.MAX_SLOT) return;
        boolean ok = save ? e.runtime().saveState(slot) : e.runtime().loadState(slot);
        LOGGER.info("Save state {} slot {} @ {} -> {}", save ? "write" : "load", slot, pos, ok);
    }

    // ------------------------------------------------------------------
    // Зрители
    // ------------------------------------------------------------------

    public static void addViewer(BlockPos pos, UUID playerId) {
        VIEWERS.computeIfAbsent(pos.immutable(), k -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    public static void removeViewer(BlockPos pos, UUID playerId) {
        Set<UUID> viewers = VIEWERS.get(pos.immutable());
        if (viewers != null) viewers.remove(playerId);
    }

    /** Убрать игрока из зрителей ВСЕХ консолей (дисконнект/смена мира). */
    public static void removeViewerEverywhere(UUID playerId) {
        for (Set<UUID> viewers : VIEWERS.values()) {
            viewers.remove(playerId);
        }
    }

    /** Снимок зрителей консоли — читается FrameSender-потоком. */
    public static Set<UUID> viewers(BlockPos pos) {
        Set<UUID> v = VIEWERS.get(pos.immutable());
        return v != null ? v : Set.of();
    }

    public static boolean hasEmulator(BlockPos pos) { return ENTRIES.containsKey(pos.immutable()); }

    public static List<String> getAvailableCores() {
        if (coreManager == null) init();
        return coreManager.getCores().stream().map(CoreManager.CoreInfo::name).toList();
    }

    /** Радиус видимости экрана ТВ в мире (видеокадры). */
    public static int videoDistance() { return VIDEO_DISTANCE; }

    /** Радиус слышимости консоли (аудиопакеты). */
    public static int audioDistance() { return AUDIO_DISTANCE; }

    /**
     * ОПТИМИЗАЦИЯ: раньше stopAll ждал до 15 с ПОСЛЕДОВАТЕЛЬНО на КАЖДОЕ
     * ядро (4 консоли = до минуты на выходе из мира). Теперь:
     * 1) всем сендерам сначала сигнал, потом join'ы (идут параллельно);
     * 2) автосейв КАЖДОЙ игры (раньше stopAll не сейвил вообще —
     *    сейвил только stopEmulator);
     * 3) ядра закрываются параллельно с ОБЩИМ дедлайном 20 с.
     */
    public static void stopAll() {
        LOGGER.info("stopAll(): shutting down {} emulator(s)", ENTRIES.size());

        // 1. Сендеры: сигнал всем сразу, потом ожидание.
        List<FrameSenderThread> senders = new ArrayList<>(FRAME_SENDERS.values());
        FRAME_SENDERS.clear();
        for (FrameSenderThread sender : senders) sender.stopSender();
        for (FrameSenderThread sender : senders) sender.stopAndJoin();

        // 2. Остановка эмуляторов + автосейв.
        List<Entry> entries = new ArrayList<>(ENTRIES.values());
        ENTRIES.clear();
        VIEWERS.clear();
        List<LibretroRuntime> runtimes = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            e.threaded().stop();
            boolean saved = SaveStateManager.saveAuto(
                    e.runtime().getCore(), e.romId(), e.runtime().getPlayerPaths());
            LOGGER.info("Auto save on stopAll {} -> {}", e.romId(), saved);
            runtimes.add(e.runtime());
        }
        if (runtimes.isEmpty()) {
            awaitShutdown(2);
            LOGGER.info("All emulators stopped.");
            return;
        }

        // 3. Параллельное закрытие ядер с общим дедлайном.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(runtimes.size(), 4), r2 -> {
                            Thread t = new Thread(r2, "retro-console-stopall");
                            t.setDaemon(true);
                            return t;
                        });
        for (LibretroRuntime runtime : runtimes) {
            pool.submit(() -> {
                try {
                    runtime.close();
                } catch (Exception ex) {
                    LOGGER.warn("Core shutdown failed: {}", ex.getMessage());
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) {
                LOGGER.warn("Some cores did not shut down within 20s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        awaitShutdown(2);
        LOGGER.info("All emulators stopped.");
    }
}