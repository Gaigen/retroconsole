package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: the client muted (or unmuted) console audio.
 *
 * <p>Volume is applied client-side, so without this the server keeps streaming
 * PCM (~176 KB/s per console at 44.1 kHz stereo16) to a player who cannot hear
 * it. Announcing the flag lets FrameSenderThread skip that player entirely.
 */
public record RetroAudioSettingsPacket(boolean muted) implements CustomPacketPayload {

    public static final Type<RetroAudioSettingsPacket> TYPE = RetroPackets.type("audio_settings");

    public static final StreamCodec<ByteBuf, RetroAudioSettingsPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, RetroAudioSettingsPacket::muted,
                    RetroAudioSettingsPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
