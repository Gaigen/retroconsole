package com.retroconsole.server;

import com.retroconsole.emu.CoreManager;
import com.retroconsole.emu.LibretroRuntime;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

public class ServerConsoles {
    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Server");
    private static final int VIEW_DISTANCE = 256;

    private static final Map<BlockPos, Entry> ENTRIES = new HashMap<>();
    private static final Map<BlockPos, Set<UUID>> VIEWERS = new HashMap<>();
    private static final Map<BlockPos, FrameSenderThread> FRAME_SENDERS = new HashMap<>();
    private static CoreManager coreManager;

    private record Entry(
            LibretroRuntime runtime,
            ThreadedEmulatorRuntime threaded,
            int[] buf,
            String coreName,
            String romId
    ) {}

    public static void init() {
        // Directories are created lazily by RetroConsolePaths on first read;
        // we just resolve them through the same source of truth here.
        Path cores = com.retroconsole.platform.RetroConsolePaths.coresDir();
        Path system = com.retroconsole.platform.RetroConsolePaths.systemDir();
        Path save = com.retroconsole.platform.RetroConsolePaths.saveDir();
        coreManager = new CoreManager(cores, system, save);
        coreManager.discoverCores();
        com.retroconsole.platform.RetroConsolePaths.logPathsSummary();
        LOGGER.info("CoreManager initialized. discovered {} cores", coreManager.getCores().size());

        // Help the player out: if there are no cores, tell them where to put them.
        if (coreManager.getCores().isEmpty()) {
            LOGGER.warn("No libretro cores found. Place .dll / .so / .dylib cores in: {}",
                    cores.toAbsolutePath().normalize());
        }
    }

    public static void startEmulator(BlockPos pos, String coreName, String romId) {
        pos = pos.immutable();
        Entry existing = ENTRIES.get(pos);
        if (existing != null) {
            if (existing.romId().equals(romId) && existing.coreName().equals(coreName)) return;
            stopEmulator(pos);
        }
        if (coreManager == null) init();
        var coreInfo = coreManager.findCore(coreName);
        if (coreInfo == null) { LOGGER.error("Core not found: {}", coreName); return; }
        Path romPath = com.retroconsole.platform.RetroConsolePaths.romsDir().resolve(romId).normalize();
        if (!romPath.toFile().exists()) { LOGGER.error("ROM not found: {}", romPath); return; }
        LibretroRuntime runtime = coreManager.loadCoreAndGame(coreInfo.path(), romPath);
        if (runtime == null) { LOGGER.error("Failed to load core {} with ROM {}", coreName, romId); return; }
        int w = Math.max(runtime.getWidth(), 1);
        int h = Math.max(runtime.getHeight(), 1);
        int[] buf = new int[w * h];
        ThreadedEmulatorRuntime threaded = new ThreadedEmulatorRuntime(runtime, w, h);
        threaded.start();

        // FrameSenderThread runs at ~60 Hz independent of Minecraft's
        // 20 Hz server tick — otherwise PS1 (and any interlaced core) shows
        // severe flicker because the client only sees one frame per tick.
        FrameSenderThread sender = new FrameSenderThread(pos, threaded);
        sender.start();

        ENTRIES.put(pos, new Entry(runtime, threaded, buf, coreName, romId));
        FRAME_SENDERS.put(pos, sender);
        LOGGER.info("Started {} emulator at {} ({}x{})", coreName, pos, w, h);
    }

    public static void stopEmulator(BlockPos pos) {
        pos = pos.immutable();
        Entry e = ENTRIES.remove(pos);
        FrameSenderThread sender = FRAME_SENDERS.remove(pos);
        if (sender != null) sender.stopSender();
        if (e != null) {
            LOGGER.info("stopEmulator({}): core={}, rom={}", pos, e.coreName(), e.romId());
            e.threaded().stop();
            e.runtime().close();
            LOGGER.info("Stopped emulator at {}", pos);
        }
        VIEWERS.remove(pos);
    }

    public static void tick(ServerLevel level) {
        // Frame sending moved off the Minecraft server tick — see
        // FrameSenderThread. Tick is kept as a registered no-op so the
        // existing @SubscribeEvent wiring stays valid and easy to extend.
    }

    public static void handleInput(BlockPos pos, int buttonId, boolean pressed) {
        Entry e = ENTRIES.get(pos.immutable());
        if (e != null) e.runtime().setButton(buttonId, pressed);
    }

    public static void handleAnalog(BlockPos pos, int stick, int axis, short value) {
        Entry e = ENTRIES.get(pos.immutable());
        if (e != null) e.runtime().setAnalog(stick, axis, value);
    }

    public static void addViewer(BlockPos pos, UUID playerId) {
        VIEWERS.computeIfAbsent(pos.immutable(), k -> new HashSet<>()).add(playerId);
    }

    public static void removeViewer(BlockPos pos, UUID playerId) {
        Set<UUID> viewers = VIEWERS.get(pos.immutable());
        if (viewers != null) viewers.remove(playerId);
    }

    public static boolean hasEmulator(BlockPos pos) { return ENTRIES.containsKey(pos.immutable()); }

    public static List<String> getAvailableCores() {
        if (coreManager == null) init();
        return coreManager.getCores().stream().map(CoreManager.CoreInfo::name).toList();
    }

    /** Public accessor so {@link FrameSenderThread} can apply the same distance check. */
    public static int viewDistance() { return VIEW_DISTANCE; }

    public static void stopAll() {
        LOGGER.info("stopAll(): shutting down {} emulator(s)", FRAME_SENDERS.size());
        for (FrameSenderThread sender : FRAME_SENDERS.values()) sender.stopSender();
        FRAME_SENDERS.clear();
        for (Entry e : ENTRIES.values()) { e.threaded().stop(); e.runtime().close(); }
        ENTRIES.clear(); VIEWERS.clear();
        LOGGER.info("All emulators stopped.");
    }
}
