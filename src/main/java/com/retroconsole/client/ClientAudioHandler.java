package com.retroconsole.client;

import com.retroconsole.network.RetroAudioPayload;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientAudioHandler {
    private static final java.util.Map<BlockPos, RetroAudioPlayer> PLAYERS = new java.util.HashMap<>();

    private ClientAudioHandler() {}

    public static void handle(RetroAudioPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PLAYERS
                .computeIfAbsent(payload.pos(), p -> new RetroAudioPlayer(
                        p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5))
                .feed(payload.sampleRate(), payload.pcm()));
    }

    public static void stop(BlockPos pos) {
        RetroAudioPlayer p = PLAYERS.remove(pos.immutable());
        if (p != null) p.close();
    }

    public static void stopAll() {
        PLAYERS.values().forEach(RetroAudioPlayer::close);
        PLAYERS.clear();
    }
}
