package com.retroconsole.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Picks the first valid PS2 BIOS file from {@code system/pcsx2/bios}. */
public final class Pcsx2BiosResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Pcsx2BiosResolver");

    private Pcsx2BiosResolver() {}

    public static String findFirstBiosFilenameFromSystemDir(Path systemDir) {
        if (systemDir == null) return null;
        return findFirstBiosFilename(systemDir.resolve("pcsx2").resolve("bios"));
    }

    public static String findFirstBiosFilename(Path biosDir) {
        if (biosDir == null) return null;
        Path preferred = biosDir.resolve("scph70000.bin");
        if (Files.isRegularFile(preferred)) {
            return preferred.getFileName().toString();
        }
        if (!Files.isDirectory(biosDir)) return null;
        try (var stream = Files.list(biosDir)) {
            return stream
                    .filter(p -> Files.isRegularFile(p) || Files.isSymbolicLink(p))
                    .filter(p -> {
                        try {
                            long size = Files.size(p);
                            return size >= 4L * 1024 * 1024 && size <= 8L * 1024 * 1024;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.warn("Failed to scan PCSX2 bios dir {}", biosDir, e);
            return null;
        }
    }
}
