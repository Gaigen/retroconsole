package com.retroconsole.client;

import com.retroconsole.network.RetroAudioPayload;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientAudioHandler {
    private static final Map<BlockPos, RetroAudioPlayer> PLAYERS = new ConcurrentHashMap<>();
    /** OpenAL feed off the MC main thread — avoids burst-delivery when the renderer is busy. */
    private static final ExecutorService AUDIO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "retro-console-audio");
        t.setDaemon(true);
        return t;
    });

    private ClientAudioHandler() {}

    public static void handle(RetroAudioPayload payload, IPayloadContext ctx) {
        BlockPos pos = payload.pos().immutable();
        AUDIO_EXEC.execute(() -> PLAYERS
                .computeIfAbsent(pos, p -> new RetroAudioPlayer(
                        p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5))
                .feed(payload.sampleRate(), payload.pcm()));
    }

    public static void stop(BlockPos pos) {
        BlockPos key = pos.immutable();
        AUDIO_EXEC.execute(() -> {
            RetroAudioPlayer p = PLAYERS.remove(key);
            if (p != null) p.close();
        });
    }

    public static void stopAll() {
        AUDIO_EXEC.execute(() -> {
            PLAYERS.values().forEach(RetroAudioPlayer::close);
            PLAYERS.clear();
        });
    }
}
