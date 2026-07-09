package com.retroconsole.client;

import com.retroconsole.network.RetroArtPacket;

import java.util.HashMap;
import java.util.Map;

/** Кэш обложек art/, полученных с сервера (в память, без записи на диск клиента). */
public final class ClientArtCache {

    private static final Map<String, byte[]> BY_FOLDER = new HashMap<>();

    private ClientArtCache() {}

    public static void clear() {
        for (String folder : BY_FOLDER.keySet()) {
            TextureCache.invalidate(serverKey(folder));
        }
        BY_FOLDER.clear();
    }

    public static void apply(RetroArtPacket pkt) {
        for (RetroArtPacket.ArtEntry e : pkt.images()) {
            BY_FOLDER.put(e.folder(), e.png());
            TextureCache.invalidate(serverKey(e.folder()));
        }
    }

    public static TextureCache.Tex get(String folder) {
        byte[] png = BY_FOLDER.get(folder);
        if (png == null) return null;
        return TextureCache.getFromBytes(serverKey(folder), png);
    }

    private static String serverKey(String folder) {
        return "server-art:" + folder;
    }
}
