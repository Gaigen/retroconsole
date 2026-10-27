package com.retroconsole.server;

import com.retroconsole.bridge.CoreModulePool;
import com.retroconsole.bridge.LibretroCore;
import com.retroconsole.config.RetroSettings;
import com.retroconsole.emu.CoreManager;
import com.retroconsole.emu.LibretroRuntime;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import com.retroconsole.network.NetAccess;
import com.retroconsole.network.RetroStopConsolePacket;
import com.retroconsole.network.payload.RetroPayloads;
import com.retroconsole.library.RpgMakerRtp;
import com.retroconsole.platform.PlayerPaths;
import com.retroconsole.platform.Pcsx2MemcardSync;
import com.retroconsole.platform.RetroConsolePaths;
import com.retroconsole.platform.SaveStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * Players who set console volume to 0 (announced via RetroAudioSettingsPacket).
     * Written on the server thread, read from FrameSender threads.
     */
    private static final Set<UUID> AUDIO_MUTED = ConcurrentHashMap.newKeySet();

    private static CoreManager coreManager;

    /** Background core shutdown — does not block server thread when breaking the block. */
    private static final java.util.concurrent.ExecutorService SHUTDOWN_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "retro-console-shutdown");
                t.setDaemon(true);
                return t;
            });

    /** Background core boot — see {@link #startEmulator}. One at a time. */
    private static final java.util.concurrent.ExecutorService START_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "retro-console-start");
                t.setDaemon(true);
                return t;
            });

    /**
     * Save-state snapshot and restore. {@code LibretroCore} hands core calls to
     * the core-owning thread and waits for the answer, and one {@code retro_run}
     * can take seconds while a game boots (mkxp-z spends ~7 s on its first
     * RGSS frames). Asking for a snapshot from the tick thread therefore stalled
     * the world for as long as that frame lasted — the "Can't keep up! Running
     * 4461ms behind" in the log. One thread keeps save and load requests in the
     * order the player made them.
     */
    private static final java.util.concurrent.ExecutorService SAVE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "retro-console-save");
                t.setDaemon(true);
                return t;
            });

    /**
     * Latest boot request per console. Booting is off-thread and takes seconds
     * (DLL slot copy, LoadLibrary, retro_load_game), so a power-off or a
     * different game in the meantime has to invalidate the boot in flight —
     * the token is what {@link #stopEmulator} removes to cancel it.
     */
    private static final ConcurrentHashMap<UUID, Long> BOOT_TOKENS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong BOOT_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

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

        if (!RetroSettings.enable3ds() && coreName.toLowerCase().contains("citra")) {
            LOGGER.error("3DS (Citra) disabled in server config — enable limits.enable3ds");
            notifyOwner(ownerId, Component.translatable("retroconsole.error.3ds_disabled"));
            return;
        }

        Path romPath = RetroConsolePaths.resolveRomFile(romId).orElse(null);
        if (romPath == null) { LOGGER.error("ROM rejected or not found: {}", romId); return; }

        // Only the cheap part stays on the caller's (server) thread. The boot —
        // core slot copy, LoadLibrary, retro_load_game — used to block the world
        // tick for seconds, which in singleplayer reads as the whole process
        // freezing, so it runs on START_EXECUTOR and publishes the Entry itself.
        PlayerPaths playerPaths = ownerId != null
                ? PlayerPaths.forPlayer(ownerId)
                : PlayerPaths.shared();
        long token = BOOT_SEQ.incrementAndGet();
        BOOT_TOKENS.put(consoleId, token);
        long startedNs = System.nanoTime();
        UUID bootId = consoleId;   // consoleId is a reassigned parameter — not capturable
        LOGGER.info("Booting {} for {} (console={}, owner={})",
                coreName, romId, bootId, ownerId);
        START_EXECUTOR.execute(() -> bootEmulator(
                bootId, coreInfo.path(), romPath, playerPaths,
                coreName, romId, ownerId, loadAuto, token, startedNs));
    }

    /**
     * Runs on {@code retro-console-start}. Publishes the console into the maps
     * once the core is up; if the request was superseded meanwhile the freshly
     * booted runtime is thrown away instead of being leaked.
     */
    private static void bootEmulator(UUID consoleId, Path corePath, Path romPath,
                                     PlayerPaths playerPaths, String coreName, String romId,
                                     UUID ownerId, boolean loadAuto, long token, long startedNs) {
        LibretroRuntime runtime = null;
        try {
            runtime = coreManager.loadCoreAndGame(corePath, romPath, playerPaths);
        } catch (Throwable t) {
            LOGGER.error("Core boot crashed for {}: {}", coreName, t.getMessage(), t);
        }

        // Claim the request: whoever removes the token owns the cleanup, so a
        // stop/power-off that landed during the load wins and this boot drops out.
        if (!BOOT_TOKENS.remove(consoleId, token)) {
            if (runtime != null) scheduleClose(runtime, consoleId);
            LOGGER.info("Boot of {} cancelled — console {} was stopped or restarted", romId, consoleId);
            return;
        }
        if (runtime == null) {
            Component message = loadFailureMessage(coreName, romId);
            LOGGER.error(message.getString());
            notifyOwnerDeferred(ownerId, message);
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
        LOGGER.info("Started {} emulator {} at {} ({}x{}, owner={}, loadAuto={}) in {} ms",
                coreName, consoleId, getPosition(consoleId), w, h, ownerId, loadAuto,
                (System.nanoTime() - startedNs) / 1_000_000L);
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
        // Cancels a boot still in flight; it sees the token gone and drops its runtime.
        BOOT_TOKENS.remove(consoleId);
        Entry e = ENTRIES.remove(consoleId);
        BlockPos pos = POSITIONS.get(consoleId);
        FrameSenderThread sender = FRAME_SENDERS.remove(consoleId);
        VIEWERS.remove(consoleId);
        PORTS.remove(consoleId);
        POSITIONS.remove(consoleId);
        if (pos != null) {
            notifyConsoleStopped(consoleId, pos);
        }
        if (e == null) {
            if (sender != null) SHUTDOWN_EXECUTOR.execute(sender::stopAndJoin);
            return;
        }
        LOGGER.info("stopEmulator({}): core={}, rom={}", consoleId, e.coreName(), e.romId());
        if (e.ownerId() != null) {
            long sec = Math.max(0, (System.currentTimeMillis() - e.startedAtMillis()) / 1000);
            ServerPlayStats.addPlaytime(e.ownerId(), e.romId(), sec);
        }
        // Teardown blocks for seconds (frame-thread join, save-state snapshot, GL
        // context destroy). It used to run on the server thread on every power-off
        // and on every game switch, freezing the world while it did.
        SHUTDOWN_EXECUTOR.execute(() -> teardown(consoleId, e, sender));
    }

    /** Frame-thread join, auto-save snapshot and core close — shutdown thread only. */
    private static void teardown(UUID consoleId, Entry e, FrameSenderThread sender) {
        if (sender != null) sender.stopAndJoin();
        e.threaded().stop();
        // A core that ended its own runtime has no live sandbox left to snapshot.
        boolean saved = e.runtime().getCore().coreExitCode() == 0
                && SaveStateManager.saveAuto(
                        e.runtime().getCore(), e.romId(), e.runtime().getPlayerPaths());
        LOGGER.info("Auto save on stop {} -> {}", e.romId(), saved);
        scheduleClose(e.runtime(), consoleId);
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
                        : RetroSettings.maxCoreSlots();
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
                            RetroSettings.maxPcsx2Sessions());
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
        MinecraftServer server = ServerAccess.getCurrentServer();
        if (server == null) return;
        ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
        if (player == null) return;
        player.sendSystemMessage(Component.literal("[RetroConsole] ").append(message));
    }

    /** Same, but for callers off the server thread (the boot does not block it). */
    private static void notifyOwnerDeferred(UUID ownerId, Component message) {
        MinecraftServer server = ServerAccess.getCurrentServer();
        if (server == null) {
            notifyOwner(ownerId, message);
            return;
        }
        server.execute(() -> notifyOwner(ownerId, message));
    }

    /** True while {@code consoleId} is still loading — it has no Entry yet. */
    public static boolean isBooting(UUID consoleId) {
        return consoleId != null && BOOT_TOKENS.containsKey(consoleId);
    }

    private static void notifyConsoleStopped(UUID consoleId, BlockPos pos) {
        MinecraftServer server = ServerAccess.getCurrentServer();
        if (server == null || !server.isRunning()) return;
        RetroStopConsolePacket packet = new RetroStopConsolePacket(consoleId);
        long radiusSq = (long) RetroSettings.notifyDistance() * RetroSettings.notifyDistance();
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            if (player.hasDisconnected()) continue;
            if (player.blockPosition().distSqr(pos) < radiusSq) {
                NetAccess.sendToPlayer(player, RetroPayloads.STOP_CONSOLE, packet);
            }
        }
    }

    public static void tick(ServerLevel level) {
        // Frame sending runs in FrameSenderThread; this poll is what notices a
        // core that ended its own runtime (see coreStoppedMessage).
        if (ENTRIES.isEmpty()) return;
        for (Map.Entry<UUID, Entry> live : List.copyOf(ENTRIES.entrySet())) {
            Entry e = live.getValue();
            if (e.runtime().getCore().coreExitCode() == 0) continue;
            Component why = coreStoppedMessage(e);
            LOGGER.info("Core runtime ended for {} ({}): {}", live.getKey(), e.romId(), why.getString());
            notifyOwner(e.ownerId(), why);
            stopEmulator(live.getKey());
        }
    }

    /**
     * Why a console stopped by itself. Cores such as mkxp-z end their sandbox
     * inside retro_run() without a libretro SHUTDOWN, so the last frame (black)
     * would keep streaming; the core's own last message names the asset it died
     * on. RPG Maker games often die on a run-time package (RTP) that the author
     * expected to be installed, so name the folder mkxp-z mounts it from.
     */
    private static Component coreStoppedMessage(Entry e) {
        String detail = e.runtime().getCore().coreExitMessage();
        if (detail != null) detail = detail.replace('\n', ' ').replace('\r', ' ').strip();
        if (detail == null || detail.isEmpty()) detail = "no details";
        List<String> missing = List.of();
        try {
            Path romPath = RetroConsolePaths.resolveRomFile(e.romId()).orElse(null);
            if (romPath != null) missing = RpgMakerRtp.missing(romPath);
        } catch (Exception ignored) {}
        if (!missing.isEmpty()) {
            List<String> hints = new ArrayList<>(missing.size());
            for (String name : missing) hints.add(RpgMakerRtp.installHint(name));
            return Component.translatable("retroconsole.error.core_stopped_rtp",
                    e.coreName(), detail, String.join(", ", missing), String.join(" / ", hints));
        }
        return Component.translatable("retroconsole.error.core_stopped", e.coreName(), detail);
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

    public static void handleKey(UUID consoleId, int retroKey, boolean pressed) {
        Entry e = ENTRIES.get(consoleId);
        if (e != null) e.runtime().setKey(retroKey, pressed);
    }

    public static void handleMouse(UUID consoleId, int dx, int dy, int buttons, int wheel) {
        Entry e = ENTRIES.get(consoleId);
        if (e == null) return;
        e.runtime().setMouseDelta(dx, dy);
        e.runtime().setMouseButtons(buttons);
        e.runtime().setMouseWheel(wheel);
    }

    public static void handleSaveState(UUID consoleId, int slot, boolean save, boolean auto) {
        Entry e = ENTRIES.get(consoleId);
        if (e == null) return;
        if (auto) {
            if (!save) return;
            SAVE_EXECUTOR.execute(() -> {
                boolean ok = SaveStateManager.saveAuto(
                        e.runtime().getCore(), e.romId(), e.runtime().getPlayerPaths());
                LOGGER.info("Auto save state @ {} -> {}", consoleId, ok);
            });
            return;
        }
        if (slot < 0 || slot > SaveStateManager.MAX_SLOT) return;
        SAVE_EXECUTOR.execute(() -> {
            boolean ok = save ? e.runtime().saveState(slot) : e.runtime().loadState(slot);
            LOGGER.info("Save state {} slot {} @ {} -> {}", save ? "write" : "load", slot, consoleId, ok);
        });
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

    /** Muted clients receive no audio packets at all — see FrameSenderThread.audioRecipients. */
    public static void setAudioMuted(UUID playerId, boolean muted) {
        // Log only on change: the client announces this once per flip, but a
        // reconnect re-announces the same state.
        if (muted) {
            if (AUDIO_MUTED.add(playerId)) LOGGER.info("Audio muted for {}", playerId);
        } else if (AUDIO_MUTED.remove(playerId)) {
            LOGGER.info("Audio unmuted for {}", playerId);
        }
    }

    public static boolean isAudioMuted(UUID playerId) {
        return AUDIO_MUTED.contains(playerId);
    }

    /** Player disconnected — drop the flag without logging an "unmuted" event. */
    public static void forgetAudioMuted(UUID playerId) {
        AUDIO_MUTED.remove(playerId);
    }

    public static boolean hasEmulator(UUID consoleId) {
        return consoleId != null && ENTRIES.containsKey(consoleId);
    }

    public static List<String> getAvailableCores() {
        if (coreManager == null) init();
        return coreManager.getCores().stream().map(CoreManager.CoreInfo::name).toList();
    }

    public static int videoDistance() { return RetroSettings.videoDistance(); }

    public static int audioDistance() { return RetroSettings.audioDistance(); }

    public static void stopAll() {
        LOGGER.info("stopAll(): shutting down {} emulator(s)", ENTRIES.size());

        // Invalidate pending boots and let the one in flight drop its runtime
        // before the maps below are cleared — otherwise it would publish a
        // console nobody shuts down.
        BOOT_TOKENS.clear();
        try {
            START_EXECUTOR.submit(() -> null).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.debug("Boot drain wait: {}", e.getMessage());
        }

        List<FrameSenderThread> senders = new ArrayList<>(FRAME_SENDERS.values());
        FRAME_SENDERS.clear();
        for (FrameSenderThread sender : senders) sender.stopSender();
        for (FrameSenderThread sender : senders) sender.stopAndJoin();

        List<Entry> entries = new ArrayList<>(ENTRIES.values());
        ENTRIES.clear();
        VIEWERS.clear();
        PORTS.clear();
        AUDIO_MUTED.clear();
        POSITIONS.clear();
        List<LibretroRuntime> runtimes = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e.ownerId() != null) {
                long sec = Math.max(0, (System.currentTimeMillis() - e.startedAtMillis()) / 1000);
                ServerPlayStats.addPlaytime(e.ownerId(), e.romId(), sec);
            }
            e.threaded().stop();
            // Same guard as stopEmulator: nothing left to snapshot after a core exit.
            boolean saved = e.runtime().getCore().coreExitCode() == 0
                    && SaveStateManager.saveAuto(
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
