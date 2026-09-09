package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** S2C: chunk of console PCM audio. */
public record RetroAudioPayload(UUID consoleId, BlockPos pos, int sampleRate, byte[] pcm)
        implements CustomPacketPayload {

    public static final Type<RetroAudioPayload> TYPE = RetroPackets.type("audio");

    public static final StreamCodec<ByteBuf, RetroAudioPayload> STREAM_CODEC =
            StreamCodec.composite(
                    RetroPackets.UUID_CODEC,  RetroAudioPayload::consoleId,
                    BlockPos.STREAM_CODEC,    RetroAudioPayload::pos,
                    ByteBufCodecs.VAR_INT,    RetroAudioPayload::sampleRate,
                    ByteBufCodecs.BYTE_ARRAY, RetroAudioPayload::pcm,
                    RetroAudioPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
