package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

import java.util.UUID;

/** S2C: chunk of console PCM audio. */
public record RetroAudioPayload(UUID consoleId, BlockPos pos, int sampleRate, byte[] pcm) {

    public static final StreamCodec<ByteBuf, RetroAudioPayload> STREAM_CODEC =
            StreamCodec.composite(
                    RetroPackets.UUID_CODEC,  RetroAudioPayload::consoleId,
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS,    RetroAudioPayload::pos,
                    ByteBufCodecs.VAR_INT,    RetroAudioPayload::sampleRate,
                    ByteBufCodecs.BYTE_ARRAY, RetroAudioPayload::pcm,
                    RetroAudioPayload::new);
}
