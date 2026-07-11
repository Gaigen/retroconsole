package com.retroconsole.bridge;

import com.retroconsole.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Per-core pool of on-disk DLL copies ("slots").
 *
 * <p>Windows maps a separate module instance per unique file path, and JNA
 * caches NativeLibrary by path. So the only reliable way to run N sessions of
 * the same libretro core in one process is N file copies — each copy gets its
 * own globals (Flycast Emulator::state, PPSSPP/LRPS2 singletons, ...).
 *
 * <p>Slot files live next to the original core as dot-prefixed names (hidden
 * from {@code CoreManager.discoverCores()}): {@code .flycast_libretro.slot0.dll}.
 * Same directory as the original keeps DLL dependency search working (PCSX2).
 *
 * <p>PCSX2 host memory uses {@code CreateFileMappingW("pcsx2_<pid>", ...)} — a
 * process-global name. Per-slot uniquification is done in {@code headless_gl_win.c}
 * (CreateFileMappingW hook), not by patching the DLL (the {@code "pcsx2"} string
 * is also the resources path prefix).
 */
public final class CoreModulePool {
    private static final Logger LOGGER = LoggerFactory.getLogger("CoreModulePool");

    /** slotPath is what goes into Native.load; key+index identify the slot. */
    public record Slot(String key, int index, Path slotPath) {}

    /** Busy slot ids ("<core>#<index>"); guarded by class monitor. */
    private static final Set<String> BUSY = new HashSet<>();

    private static volatile String lastRefuseReason;
    private static volatile String lastRefuseKey;
    private static volatile Object[] lastRefuseArgs = new Object[0];

    private CoreModulePool() {}

    public static String lastRefuseReason() { return lastRefuseReason; }

    public static String lastRefuseKey() { return lastRefuseKey; }

    public static Object[] lastRefuseArgs() {
        Object[] a = lastRefuseArgs;
        return a != null ? a : new Object[0];
    }

    private static void clearRefuse() {
        lastRefuseReason = null;
        lastRefuseKey = null;
        lastRefuseArgs = new Object[0];
    }

    private static void refuse(String key, Object[] args, String englishLog) {
        lastRefuseKey = key;
        lastRefuseArgs = args != null ? args : new Object[0];
        lastRefuseReason = englishLog;
        LOGGER.error(englishLog);
    }

    /**
     * Acquire a free slot and materialize its DLL copy. Slots grow as needed
     * unless {@link ModConfig#maxCoreSlots()} is &gt; 0.
     */
    public static synchronized Slot acquire(Path corePath) {
        clearRefuse();
        String key = key(corePath);
        int cap = ModConfig.maxCoreSlots();
        int copyFailures = 0;
        for (int i = 0; cap <= 0 || i < cap; i++) {
            String id = key + "#" + i;
            if (BUSY.contains(id)) continue;
            Path slotPath = slotPath(corePath, i);
            boolean fresh = !Files.exists(slotPath);
            if (!ensureCopy(corePath, slotPath)) {
                if (fresh && ++copyFailures >= 3) {
                    refuse("retroconsole.error.core_slot_copy",
                            new Object[]{key},
                            "Cannot copy core module for " + key + " (disk full or permissions)");
                    return null;
                }
                continue;
            }
            BUSY.add(id);
            LOGGER.info("Module slot acquired: {} -> {}", key, slotPath.getFileName());
            return new Slot(key, i, slotPath);
        }
        if (cap > 0) {
            refuse("retroconsole.error.core_slot_cap",
                    new Object[]{cap},
                    "Core slot limit (" + cap + ") reached for " + key
                            + " — stop another console or raise limits.maxCoreSlots");
        } else {
            refuse("retroconsole.error.core_slot_copy",
                    new Object[]{key},
                    "No free module slot for " + key);
        }
        return null;
    }

    public static synchronized void release(Slot slot) {
        if (slot != null && BUSY.remove(slot.key() + "#" + slot.index())) {
            LOGGER.info("Module slot released: {}#{}", slot.key(), slot.index());
        }
    }

    private static String key(Path corePath) {
        return corePath.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    /** Same folder as original, dot-prefixed — DLL deps resolve; discoverCores skips. */
    private static Path slotPath(Path corePath, int index) {
        String name = corePath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return corePath.toAbsolutePath().getParent()
                .resolve("." + base + ".slot" + index + ext);
    }

    /** Copy/refresh the slot file. False => slot unusable (e.g. mapped + stale). */
    private static boolean ensureCopy(Path original, Path slotPath) {
        try {
            if (Files.exists(slotPath)
                    && Files.size(slotPath) == Files.size(original)
                    && Files.getLastModifiedTime(slotPath)
                            .compareTo(Files.getLastModifiedTime(original)) >= 0) {
                return true; // up to date
            }
            Files.copy(original, slotPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (IOException e) {
            // Stale copy still mapped by a live/poisoned session — cannot overwrite.
            LOGGER.warn("Cannot prepare slot file {}: {}", slotPath, e.getMessage());
            return false;
        }
    }
}
