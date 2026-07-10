package com.retroconsole.bridge;

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

    private CoreModulePool() {}

    /** Optional safety valve: -Dretroconsole.maxSlots=N (default 0 = unlimited). */
    private static final int MAX_SLOTS = Integer.getInteger("retroconsole.maxSlots", 0);

    /**
     * Acquire a free slot and materialize its DLL copy. Slots are UNBOUNDED:
     * indices grow as needed (slot0, slot1, ...) — start as many sessions as
     * you like, RAM/GPU is the only natural limit.
     */
    public static synchronized Slot acquire(Path corePath) {
        String key = key(corePath);
        int copyFailures = 0;
        for (int i = 0; MAX_SLOTS <= 0 || i < MAX_SLOTS; i++) {
            String id = key + "#" + i;
            if (BUSY.contains(id)) continue;
            Path slotPath = slotPath(corePath, i);
            boolean fresh = !Files.exists(slotPath);
            if (!ensureCopy(corePath, slotPath)) {
                // A locked stale leftover -> just take the next index. But if
                // even a FRESH copy to a new file fails (disk full, no perms),
                // it is systemic — further indices won't help, bail out.
                if (fresh && ++copyFailures >= 3) {
                    LOGGER.error("Giving up after {} fresh-copy failures for {}", copyFailures, key);
                    return null;
                }
                continue;
            }
            BUSY.add(id);
            LOGGER.info("Module slot acquired: {} -> {}", key, slotPath.getFileName());
            return new Slot(key, i, slotPath);
        }
        LOGGER.error("Slot cap {} reached for {} (see -Dretroconsole.maxSlots)", MAX_SLOTS, key);
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
