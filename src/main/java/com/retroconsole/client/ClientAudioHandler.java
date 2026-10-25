package com.retroconsole.client;

import com.retroconsole.client.library.SoundPrefs;
import com.retroconsole.network.RetroAudioPayload;
import com.retroconsole.network.RetroAudioSettingsPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientAudioHandler {
    private static final Map<UUID, RetroAudioPlayer> PLAYERS = new ConcurrentHashMap<>();

    /** Slider value below this counts as muted; matches the slider's 1% steps. */
    private static final float MUTE_EPSILON = 0.005f;

    /** Last mute state announced to the server; null = not announced yet this session. */
    private static Boolean serverMuted;

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
        announceMute(volume <= MUTE_EPSILON);
    }

    /**
     * Tell the server whether this client wants audio at all. Called on login and
     * when a TvScreen opens (covers a login that raced the connection).
     */
    public static void syncMuteToServer() {
        announceMute(SoundPrefs.volume() <= MUTE_EPSILON);
    }

    /**
     * Only on change: the server uses this to drop the player from the audio
     * recipient list, so an unmuted player keeps his normal ~176 KB/s stream.
     */
    private static void announceMute(boolean muted) {
        if (serverMuted != null && serverMuted == muted) return;
        if (Minecraft.getInstance().getConnection() == null) return; // retried on next call
        serverMuted = muted;
        PacketDistributor.sendToServer(new RetroAudioSettingsPacket(muted));
    }

    public static void stop(UUID consoleId) {
        if (consoleId == null) return;
        AUDIO_EXEC.execute(() -> {
            RetroAudioPlayer p = PLAYERS.remove(consoleId);
            if (p != null) p.close();
        });
    }

    public static void stopAll() {
        // New server / new session: the server forgot our mute flag, announce it again.
        serverMuted = null;
        AUDIO_EXEC.execute(() -> {
            PLAYERS.values().forEach(RetroAudioPlayer::close);
            PLAYERS.clear();
        });
    }
}
