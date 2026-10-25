package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** C2S: subscribe/unsubscribe to console frames at the given position. */
public record RetroViewPacket(
        BlockPos pos,
        boolean watching
) {

    public static final StreamCodec<ByteBuf, RetroViewPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroViewPacket::pos,
                    ByteBufCodecs.BOOL,    RetroViewPacket::watching,
                    RetroViewPacket::new);
}
