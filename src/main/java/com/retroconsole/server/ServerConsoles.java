package com.retroconsole.server;

import com.retroconsole.bridge.CoreModulePool;
import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.config.ModConfig;
import com.retroconsole.emu.CoreManager;
import com.retroconsole.emu.LibretroRuntime;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import com.retroconsole.network.RetroStopConsolePacket;
import com.retroconsole.platform.PlayerPaths;
import com.retroconsole.platform.Pcsx2MemcardSync;
import com.retroconsole.platform.RetroConsolePaths;
import com.retroconsole.platform.SaveStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

    // ConcurrentHashMap: mutations on server thread, reads from FrameSender threads.
    private static final ConcurrentHashMap<BlockPos, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, Set<UUID>> VIEWERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockPos, FrameSenderThread> FRAME_SENDERS = new ConcurrentHashMap<>();

    private static CoreManager coreManager;

    /** Background core shutdown — does not block server thread when breaking the block. */
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
            UUID ownerId,
            long startedAtMillis
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

        if (!ModConfig.enable3ds() && coreName.toLowerCase().contains("citra")) {
            LOGGER.error("3DS (Citra) disabled in server config — enable limits.enable3ds");
            notifyOwner(ownerId, Component.translatable("retroconsole.error.3ds_disabled"));
            return;
        }

        Path romPath = RetroConsolePaths.resolveRomFile(romId).orElse(null);
        if (romPath == null) { LOGGER.error("ROM rejected or not found: {}", romId); return; }

        PlayerPaths playerPaths = ownerId != null
                ? PlayerPaths.forPlayer(ownerId)
                : PlayerPaths.shared();

        LibretroRuntime runtime = coreManager.loadCoreAndGame(coreInfo.path(), romPath, playerPaths);
        if (runtime == null) {
            Component message = loadFailureMessage(coreName, romId);
            LOGGER.error(message.getString());
            notifyOwner(ownerId, message);
            return;
        }

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

        ENTRIES.put(pos, new Entry(runtime, threaded, buf, coreName, romId, ownerId,
                System.currentTimeMillis()));
        FRAME_SENDERS.put(pos, sender);
        if (ownerId != null) {
            ServerPlayStats.onLaunch(ownerId, romId);
        }
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
            if (e.ownerId() != null) {
                long sec = Math.max(0, (System.currentTimeMillis() - e.startedAtMillis()) / 1000);
                ServerPlayStats.addPlaytime(e.ownerId(), e.romId(), sec);
            }
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

    private static Component loadFailureMessage(String coreName, String romId) {
        String refuseKey = CoreModulePool.lastRefuseKey();
        if (refuseKey != null) {
            if ("retroconsole.error.core_slot_cap".equals(refuseKey)) {
                Object[] args = CoreModulePool.lastRefuseArgs();
                int cap = args.length > 0 && args[0] instanceof Number n
                        ? n.intValue()
                        : ModConfig.maxCoreSlots();
                return Component.translatable(refuseKey, cap, coreName);
            }
            return Component.translatable(refuseKey, CoreModulePool.lastRefuseArgs());
        }
        if (coreName.toLowerCase().contains("pcsx2")) {
            String pcsx2 = Pcsx2MemcardSync.lastRefuseReason();
            if (pcsx2 != null && !pcsx2.isBlank()) {
                if (pcsx2.contains("session limit")) {
                    return Component.translatable(
                            "retroconsole.error.pcsx2_session_cap",
                            ModConfig.maxPcsx2Sessions());
                }
                if (pcsx2.toLowerCase().contains("bios")) {
                    return Component.translatable("retroconsole.error.pcsx2_bios");
                }
                return Component.literal(pcsx2);
            }
        }
        return Component.translatable("retroconsole.error.load_failed", coreName, romId);
    }

    private static void notifyOwner(UUID ownerId, Component message) {
        if (ownerId == null || message == null) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
        if (player == null) return;
        player.sendSystemMessage(Component.literal("[RetroConsole] ").append(message));
    }

    private static void notifyOwner(UUID ownerId, String message) {
        if (message == null || message.isBlank()) return;
        notifyOwner(ownerId, Component.literal(message));
    }

    private static void notifyConsoleStopped(BlockPos pos) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isRunning()) return;
        RetroStopConsolePacket packet = new RetroStopConsolePacket(pos);
        long radiusSq = (long) ModConfig.notifyDistance() * ModConfig.notifyDistance();
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

    public static void handlePointer(BlockPos pos, short x, short y, boolean pressed) {
        Entry e = ENTRIES.get(pos.immutable());
        if (e != null) e.runtime().setPointer(x, y, pressed);
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

    public static void addViewer(BlockPos pos, UUID playerId) {
        VIEWERS.computeIfAbsent(pos.immutable(), k -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    public static void removeViewer(BlockPos pos, UUID playerId) {
        Set<UUID> viewers = VIEWERS.get(pos.immutable());
        if (viewers != null) viewers.remove(playerId);
    }

    /** Remove player from viewers of ALL consoles (disconnect / dimension change). */
    public static void removeViewerEverywhere(UUID playerId) {
        for (Set<UUID> viewers : VIEWERS.values()) {
            viewers.remove(playerId);
        }
    }

    /** Snapshot of console viewers — read by FrameSender thread. */
    public static Set<UUID> viewers(BlockPos pos) {
        Set<UUID> v = VIEWERS.get(pos.immutable());
        return v != null ? v : Set.of();
    }

    public static boolean hasEmulator(BlockPos pos) { return ENTRIES.containsKey(pos.immutable()); }

    public static List<String> getAvailableCores() {
        if (coreManager == null) init();
        return coreManager.getCores().stream().map(CoreManager.CoreInfo::name).toList();
    }

    /** In-world TV screen visibility radius (video frames). */
    public static int videoDistance() { return ModConfig.videoDistance(); }

    /** Console audio hearing radius (audio packets). */
    public static int audioDistance() { return ModConfig.audioDistance(); }

    /**
     * OPTIMIZATION: stopAll used to wait up to 15s SEQUENTIALLY per core (4 consoles =
     * up to a minute on world exit). Now:
     * 1) signal all senders first, then join (in parallel);
     * 2) autosave EVERY game (stopAll previously saved nothing — only stopEmulator did);
     * 3) cores close in parallel with a shared 20s deadline.
     */
    public static void stopAll() {
        LOGGER.info("stopAll(): shutting down {} emulator(s)", ENTRIES.size());

        // 1. Senders: signal all, then wait.
        List<FrameSenderThread> senders = new ArrayList<>(FRAME_SENDERS.values());
        FRAME_SENDERS.clear();
        for (FrameSenderThread sender : senders) sender.stopSender();
        for (FrameSenderThread sender : senders) sender.stopAndJoin();

        // 2. Stop emulators + autosave.
        List<Entry> entries = new ArrayList<>(ENTRIES.values());
        ENTRIES.clear();
        VIEWERS.clear();
        List<LibretroRuntime> runtimes = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.ownerId() != null) {
                long sec = Math.max(0, (System.currentTimeMillis() - e.startedAtMillis()) / 1000);
                ServerPlayStats.addPlaytime(e.ownerId(), e.romId(), sec);
            }
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

        // 3. Parallel core shutdown with shared deadline.
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