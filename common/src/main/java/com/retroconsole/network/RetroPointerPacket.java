package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/** C2S: absolute stylus/touch coordinates in libretro POINTER space. */
public record RetroPointerPacket(
        BlockPos pos,
        short x,
        short y,
        boolean pressed
) {

    public static final StreamCodec<ByteBuf, RetroPointerPacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroPointerPacket::pos,
                    ByteBufCodecs.SHORT, RetroPointerPacket::x,
                    ByteBufCodecs.SHORT, RetroPointerPacket::y,
                    ByteBufCodecs.BOOL, RetroPointerPacket::pressed,
                    RetroPointerPacket::new);
}
