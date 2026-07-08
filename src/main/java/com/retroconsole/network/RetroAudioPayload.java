package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C: порция PCM-аудио консоли. */
public record RetroAudioPayload(BlockPos pos, int sampleRate, byte[] pcm)
        implements CustomPacketPayload {

    public static final Type<RetroAudioPayload> TYPE = RetroPackets.type("audio");

    public static final StreamCodec<ByteBuf, RetroAudioPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,    RetroAudioPayload::pos,
                    ByteBufCodecs.VAR_INT,    RetroAudioPayload::sampleRate,
                    ByteBufCodecs.BYTE_ARRAY, RetroAudioPayload::pcm,
                    RetroAudioPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
