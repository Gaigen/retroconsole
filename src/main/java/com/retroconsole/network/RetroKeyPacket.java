package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: raw keyboard press/release for a console.
 * {@code key} is a libretro RETROK code (SDL 1.2 values), see
 * {@code client.input.KeyboardMapper}.
 */
public record RetroKeyPacket(
        BlockPos pos,
        int key,
        boolean pressed
) implements CustomPacketPayload {

    public static final Type<RetroKeyPacket> TYPE = RetroPackets.type("key");

    public static final StreamCodec<ByteBuf, RetroKeyPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroKeyPacket::pos,
                    ByteBufCodecs.VAR_INT, RetroKeyPacket::key,
                    ByteBufCodecs.BOOL,    RetroKeyPacket::pressed,
                    RetroKeyPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
