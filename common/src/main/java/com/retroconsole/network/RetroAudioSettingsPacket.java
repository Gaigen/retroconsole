package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/**
 * C2S: the client muted (or unmuted) console audio.
 *
 * <p>Volume is applied client-side, so without this the server keeps streaming
 * PCM (~176 KB/s per console at 44.1 kHz stereo16) to a player who cannot hear
 * it. Announcing the flag lets FrameSenderThread skip that player entirely.
 */
public record RetroAudioSettingsPacket(boolean muted) {

    public static final StreamCodec<ByteBuf, RetroAudioSettingsPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, RetroAudioSettingsPacket::muted,
                    RetroAudioSettingsPacket::new);
}
