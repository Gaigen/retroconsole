package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** C2S: absolute stylus/touch coordinates in libretro POINTER space. */
public record RetroPointerPacket(
        BlockPos pos,
        short x,
        short y,
        boolean pressed
) {

    public static final StreamCodec<ByteBuf, RetroPointerPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroPointerPacket::pos,
                    ByteBufCodecs.SHORT, RetroPointerPacket::x,
                    ByteBufCodecs.SHORT, RetroPointerPacket::y,
                    ByteBufCodecs.BOOL, RetroPointerPacket::pressed,
                    RetroPointerPacket::new);
}
