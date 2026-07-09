package com.retroconsole.server;

import com.retroconsole.network.RetroArtPacket;
import com.retroconsole.platform.RetroConsolePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Чтение art/*.png с диска сервера для отправки клиенту. */
public final class ServerArt {

    private static final Logger LOGGER = LoggerFactory.getLogger("RetroConsole-Art");

    private ServerArt() {}

    public static List<RetroArtPacket.ArtEntry> loadForFolders(Collection<String> folders) {
        Path root = RetroConsolePaths.artDir().toAbsolutePath().normalize();
        List<RetroArtPacket.ArtEntry> out = new ArrayList<>();
        for (String folder : folders) {
            if (!safeFolder(folder)) continue;
            Path png = root.resolve(folder + ".png").normalize();
            if (!png.startsWith(root) || !Files.isRegularFile(png)) continue;
            try {
                long size = Files.size(png);
                if (size <= 0 || size > RetroArtPacket.MAX_PNG_BYTES) {
                    LOGGER.warn("Skip art {}: size {} bytes", png.getFileName(), size);
                    continue;
                }
                byte[] data = Files.readAllBytes(png);
                if (looksLikePng(data)) {
                    out.add(new RetroArtPacket.ArtEntry(folder, data));
                }
            } catch (IOException ex) {
                LOGGER.warn("Failed to read art {}: {}", png, ex.getMessage());
            }
        }
        return out;
    }

    private static boolean safeFolder(String folder) {
        return folder != null && !folder.isEmpty() && folder.matches("[a-zA-Z0-9_\\-]+");
    }

    private static boolean looksLikePng(byte[] data) {
        return data.length >= 8
                && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }
}
