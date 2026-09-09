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
    private static final ConcurrentHashMap<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, BlockPos> POSITIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<UUID>> VIEWERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, FrameSenderThread> FRAME_SENDERS = new ConcurrentHashMap<>();

    /** Co-op port assignments: consoleId -> (player UUID -> libretro port). Owner = port 0, P2 = port 1. */
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Integer>> PORTS =
            new ConcurrentHashMap<>();

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

    public static void startEmulator(UUID consoleId, BlockPos pos, String coreName, String romId, UUID ownerId) {
        startEmulator(consoleId, pos, coreName, romId, ownerId, false);
    }

    public static void startEmulator(UUID consoleId, BlockPos pos, String coreName, String romId,
                                     UUID ownerId, boolean loadAuto) {
        consoleId = Objects.requireNonNull(consoleId);
        pos = pos.immutable();
        POSITIONS.put(consoleId, pos);

        Entry existing = ENTRIES.get(consoleId);
        if (existing != null) {
            if (existing.romId().equals(romId) && existing.coreName().equals(coreName)
                    && Objects.equals(existing.ownerId(), ownerId)) {
                return;
            }
            stopEmulator(consoleId);
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

        FrameSenderThread sender = new FrameSenderThread(consoleId, threaded, runtime);
        sender.start();

        ENTRIES.put(consoleId, new Entry(runtime, threaded, buf, coreName, romId, ownerId,
                System.currentTimeMillis()));
        FRAME_SENDERS.put(consoleId, sender);
        ConcurrentHashMap<UUID, Integer> portMap = new ConcurrentHashMap<>();
        if (ownerId != null) portMap.put(ownerId, 0);
        PORTS.put(consoleId, portMap);
        if (ownerId != null) {
            ServerPlayStats.onLaunch(ownerId, romId);
        }
        LOGGER.info("Started {} emulator {} at {} ({}x{}, owner={}, loadAuto={})",
                coreName, consoleId, pos, w, h, ownerId, loadAuto);
    }

    public static void updatePosition(UUID consoleId, BlockPos pos) {
        if (consoleId == null) return;
        pos = pos.immutable();
        BlockPos prev = POSITIONS.put(consoleId, pos);
        if (prev != null && !prev.equals(pos)) {
            LOGGER.debug("Console {} moved {} -> {}", consoleId, prev.toShortString(), pos.toShortString());
        }
    }

    public static BlockPos getPosition(UUID consoleId) {
        BlockPos pos = POSITIONS.get(consoleId);
        return pos != null ? pos : BlockPos.ZERO;
    }

    public static void stopEmulator(UUID consoleId) {
        if (consoleId == null) return;
        Entry e = ENTRIES.remove(consoleId);
        BlockPos pos = POSITIONS.get(consoleId);
        FrameSenderThread sender = FRAME_SENDERS.remove(consoleId);
        if (sender != null) sender.stopAndJoin();
        if (e != null) {
            LOGGER.info("stopEmulator({}): core={}, rom={}", consoleId, e.coreName(), e.romId());
            if (e.ownerId() != null) {
                long sec = Math.max(0, (System.currentTimeMillis() - e.startedAtMillis()) / 1000);
                ServerPlayStats.addPlaytime(e.ownerId(), e.romId(), sec);
            }
            e.threaded().stop();
            boolean saved = SaveStateManager.saveAuto(
                    e.runtime().getCore(), e.romId(), e.runtime().getPlayerPaths());
            LOGGER.info("Auto save on stop {} -> {}", e.romId(), saved);
            scheduleClose(e.runtime(), consoleId);
        }
        if (pos != null) {
            notifyConsoleStopped(consoleId, pos);
        }
        VIEWERS.remove(consoleId);
        PORTS.remove(consoleId);
        POSITIONS.remove(consoleId);
    }

    private static void scheduleClose(LibretroRuntime runtime, UUID consoleId) {
        SHUTDOWN_EXECUTOR.submit(() -> {
            try {
                runtime.close();
                LOGGER.info("Core shutdown finished for {}", consoleId);
            } catch (Exception ex) {
                LOGGER.warn("Core shutdown failed for {}: {}", consoleId, ex.getMessage());
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

    private static void notifyConsoleStopped(UUID consoleId, BlockPos pos) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isRunning()) return;
        RetroStopConsolePacket packet = new RetroStopConsolePacket(consoleId);
        long radiusSq = (long) ModConfig.notifyDistance() * ModConfig.notifyDistance();
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (player.hasDisconnected()) continue;
            if (player.blockPosition().distSqr(pos) < radiusSq) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    public static void tick(ServerLevel level) {
        // Frame sending runs in FrameSenderThread; tick kept for future extensions.
    }

    public static LibretroCore getCore(UUID consoleId) {
        Entry e = ENTRIES.get(consoleId);
        return e != null ? e.runtime().getCore() : null;
    }

    public static void handleInput(UUID consoleId, int buttonId, boolean pressed) {
        handleInput(consoleId, 0, buttonId, pressed);
    }

    public static void handleInput(UUID consoleId, int port, int buttonId, boolean pressed) {
        Entry e = ENTRIES.get(consoleId);
        if (e != null) e.runtime().setButton(port, buttonId, pressed);
    }

    public static void handleAnalog(UUID consoleId, int stick, int axis, short value) {
        handleAnalog(consoleId, 0, stick, axis, value);
    }

    public static void handleAnalog(UUID consoleId, int port, int stick, int axis, short value) {
        Entry e = ENTRIES.get(consoleId);
        if (e != null) e.runtime().setAnalog(port, stick, axis, value);
    }

    public static void handlePointer(UUID consoleId, short x, short y, boolean pressed) {
        Entry e = ENTRIES.get(consoleId);
        if (e != null) e.runtime().setPointer(x, y, pressed);
    }

    public static void handleSaveState(UUID consoleId, int slot, boolean save, boolean auto) {
        Entry e = ENTRIES.get(consoleId);
        if (e == null) return;
        if (auto) {
            if (!save) return;
            boolean ok = SaveStateManager.saveAuto(
                    e.runtime().getCore(), e.romId(), e.runtime().getPlayerPaths());
            LOGGER.info("Auto save state @ {} -> {}", consoleId, ok);
            return;
        }
        if (slot < 0 || slot > SaveStateManager.MAX_SLOT) return;
        boolean ok = save ? e.runtime().saveState(slot) : e.runtime().loadState(slot);
        LOGGER.info("Save state {} slot {} @ {} -> {}", save ? "write" : "load", slot, consoleId, ok);
    }

    public static void addViewer(UUID consoleId, UUID playerId) {
        VIEWERS.computeIfAbsent(consoleId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    public static void removeViewer(UUID consoleId, UUID playerId) {
        Set<UUID> viewers = VIEWERS.get(consoleId);
        if (viewers != null) viewers.remove(playerId);
    }

    public static void removeViewerEverywhere(UUID playerId) {
        for (Set<UUID> viewers : VIEWERS.values()) {
            viewers.remove(playerId);
        }
    }

    public static int getPort(UUID consoleId, UUID playerId) {
        ConcurrentHashMap<UUID, Integer> portMap = PORTS.get(consoleId);
        if (portMap == null) return 0;
        Integer port = portMap.get(playerId);
        return port != null ? port : 0;
    }

    public static boolean joinCoop(UUID consoleId, UUID playerId) {
        ConcurrentHashMap<UUID, Integer> portMap = PORTS.get(consoleId);
        if (portMap == null) return false;
        Integer existing = portMap.get(playerId);
        if (existing != null && existing == 1) return true;
        if (portMap.containsValue(1)) return false;
        portMap.put(playerId, 1);
        LOGGER.info("Co-op: player {} joined as P2 at {}", playerId, consoleId);
        return true;
    }

    public static void leaveCoop(UUID consoleId, UUID playerId) {
        ConcurrentHashMap<UUID, Integer> portMap = PORTS.get(consoleId);
        if (portMap == null) return;
        Integer removed = portMap.remove(playerId);
        if (removed != null && removed == 1) {
            LOGGER.info("Co-op: player {} left P2 at {}", playerId, consoleId);
        }
    }

    public static boolean isOwner(UUID consoleId, UUID playerId) {
        Entry e = ENTRIES.get(consoleId);
        return e != null && playerId.equals(e.ownerId());
    }

    public static void releasePortEverywhere(UUID playerId) {
        for (ConcurrentHashMap<UUID, Integer> portMap : PORTS.values()) {
            portMap.remove(playerId);
        }
    }

    public static Set<UUID> viewers(UUID consoleId) {
        Set<UUID> v = VIEWERS.get(consoleId);
        return v != null ? v : Set.of();
    }

    public static boolean hasEmulator(UUID consoleId) {
        return consoleId != null && ENTRIES.containsKey(consoleId);
    }

    public static List<String> getAvailableCores() {
        if (coreManager == null) init();
        return coreManager.getCores().stream().map(CoreManager.CoreInfo::name).toList();
    }

    public static int videoDistance() { return ModConfig.videoDistance(); }

    public static int audioDistance() { return ModConfig.audioDistance(); }

    public static void stopAll() {
        LOGGER.info("stopAll(): shutting down {} emulator(s)", ENTRIES.size());

        List<FrameSenderThread> senders = new ArrayList<>(FRAME_SENDERS.values());
        FRAME_SENDERS.clear();
        for (FrameSenderThread sender : senders) sender.stopSender();
        for (FrameSenderThread sender : senders) sender.stopAndJoin();

        List<Entry> entries = new ArrayList<>(ENTRIES.values());
        ENTRIES.clear();
        VIEWERS.clear();
        PORTS.clear();
        POSITIONS.clear();
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
