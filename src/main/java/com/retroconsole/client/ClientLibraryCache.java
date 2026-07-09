package com.retroconsole.client;

import com.retroconsole.network.RetroLibraryPacket;
import com.retroconsole.network.RetroLibraryRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Кэш ответов серверного каталога ROM/ядер. */
public final class ClientLibraryCache {

    private static final Map<BlockPos, RetroLibraryPacket> READY = new ConcurrentHashMap<>();

    private ClientLibraryCache() {}

    /** true — каталог нужно запрашивать с сервера (dedicated / чужой мир). */
    public static boolean useServerLibrary() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && !mc.isLocalServer();
    }

    public static void request(BlockPos consolePos) {
        BlockPos key = consolePos.immutable();
        READY.remove(key);
        ClientPlayStatsCache.clear();
        ClientArtCache.clear();
        PacketDistributor.sendToServer(new RetroLibraryRequestPacket(key));
    }

    public static void onReceived(RetroLibraryPacket pkt) {
        READY.put(pkt.consolePos().immutable(), pkt);
    }

    public static Optional<RetroLibraryPacket> poll(BlockPos consolePos) {
        return Optional.ofNullable(READY.remove(consolePos.immutable()));
    }
}
