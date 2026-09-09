package com.retroconsole.client;

import com.retroconsole.client.library.SoundPrefs;
import com.retroconsole.network.RetroAudioPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientAudioHandler {
    private static final Map<UUID, RetroAudioPlayer> PLAYERS = new ConcurrentHashMap<>();
    private static final ExecutorService AUDIO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "retro-console-audio");
        t.setDaemon(true);
        return t;
    });

    private ClientAudioHandler() {}

    public static void handle(RetroAudioPayload payload, IPayloadContext ctx) {
        UUID consoleId = payload.consoleId();
        var pos = payload.pos();
        AUDIO_EXEC.execute(() -> PLAYERS
                .computeIfAbsent(consoleId, id -> {
                    RetroAudioPlayer player = new RetroAudioPlayer(
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    player.setGain(SoundPrefs.volume());
                    return player;
                })
                .updatePosition(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                .feed(payload.sampleRate(), payload.pcm()));
    }

    public static void setVolume(float volume) {
        AUDIO_EXEC.execute(() -> PLAYERS.values().forEach(p -> p.setGain(volume)));
    }

    public static void stop(UUID consoleId) {
        if (consoleId == null) return;
        AUDIO_EXEC.execute(() -> {
            RetroAudioPlayer p = PLAYERS.remove(consoleId);
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
