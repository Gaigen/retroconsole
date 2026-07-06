package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RetroAudioPayload(BlockPos pos, int sampleRate, byte[] pcm)
        implements CustomPacketPayload {

    public static final Type<RetroAudioPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "audio"));

    public static final StreamCodec<FriendlyByteBuf, RetroAudioPayload> STREAM_CODEC =
            StreamCodec.of(RetroAudioPayload::write, RetroAudioPayload::read);

    private static void write(FriendlyByteBuf buf, RetroAudioPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeVarInt(p.sampleRate);
        buf.writeByteArray(p.pcm);
    }

    private static RetroAudioPayload read(FriendlyByteBuf buf) {
        return new RetroAudioPayload(buf.readBlockPos(), buf.readVarInt(), buf.readByteArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
