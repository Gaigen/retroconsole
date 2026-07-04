package com.retroconsole.server;

import com.retroconsole.emu.CoreManager;
import com.retroconsole.emu.LibretroRuntime;
import com.retroconsole.emu.ThreadedEmulatorRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

public class ServerConsoles {
    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Server");
    private static final int VIEW_DISTANCE = 256;

    private static final Map<BlockPos, Entry> ENTRIES = new HashMap<>();
    private static final Map<BlockPos, Set<UUID>> VIEWERS = new HashMap<>();
    private static CoreManager coreManager;

    // DEBUG: per-emulator frame send/skip stats. Last emitted at DEBUG_LOG_PERIOD_S.
    private static final double DEBUG_LOG_PERIOD_S = 2.0;
    private static long dbgLastTickLogMs = 0;
    private static long dbgSendAttempts = 0;
    private static long dbgSkipNoFrame = 0;
    private static long dbgSkipZeroSize = 0;
    private static long dbgSkipBufResize = 0;
    private static int dbgLastW = -1;
    private static int dbgLastH = -1;

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
        ENTRIES.put(pos, new Entry(runtime, threaded, buf, coreName, romId));
        LOGGER.info("Started {} emulator at {} ({}x{})", coreName, pos, w, h);
    }

    public static void stopEmulator(BlockPos pos) {
        pos = pos.immutable();
        Entry e = ENTRIES.remove(pos);
        if (e != null) { e.threaded().stop(); e.runtime().close(); LOGGER.info("Stopped emulator at {}", pos); }
        VIEWERS.remove(pos);
    }

    public static void tick(ServerLevel level) {
        for (Map.Entry<BlockPos, Entry> mapEntry : ENTRIES.entrySet()) {
            BlockPos pos = mapEntry.getKey();
            Entry e = mapEntry.getValue();
            boolean hasFrame = e.threaded().pollFrame(e.buf());
            if (!hasFrame) { dbgSkipNoFrame++; continue; }
            int w = e.threaded().getCurrentWidth();
            int h = e.threaded().getCurrentHeight();
            if (w <= 0 || h <= 0) { dbgSkipZeroSize++; continue; }
            int needed = w * h;
            if (e.buf().length != needed) {
                dbgSkipBufResize++;
                e = new Entry(e.runtime(), e.threaded(), new int[needed], e.coreName(), e.romId());
                ENTRIES.put(pos, e);
                e.threaded().pollFrame(e.buf());
            }
            for (int i = 0; i < e.buf().length; i++)
                e.buf()[i] = 0xFF000000 | (e.buf()[i] & 0x00FFFFFF);
            dbgSendAttempts++;
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                if (player.level() != level) continue;
                if (player.blockPosition().distSqr(pos) < VIEW_DISTANCE * VIEW_DISTANCE)
                    ServerTickHandler.sendFrameToPlayer(player, pos, e.buf(), w, h);
            }
            dbgLastW = w;
            dbgLastH = h;
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - dbgLastTickLogMs >= (long)(DEBUG_LOG_PERIOD_S * 1000)) {
            LOGGER.info("DEBUG tick: in last {}s — sendAttempts={}, skipNoFrame={}, skipZero={}, skipBufResize={}, lastSize={}x{}",
                    DEBUG_LOG_PERIOD_S, dbgSendAttempts, dbgSkipNoFrame, dbgSkipZeroSize, dbgSkipBufResize, dbgLastW, dbgLastH);
            dbgSendAttempts = dbgSkipNoFrame = dbgSkipZeroSize = dbgSkipBufResize = 0;
            dbgLastTickLogMs = nowMs;
        }
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

    public static void stopAll() {
        for (Entry e : ENTRIES.values()) { e.threaded().stop(); e.runtime().close(); }
        ENTRIES.clear(); VIEWERS.clear();
        LOGGER.info("All emulators stopped.");
    }
}
