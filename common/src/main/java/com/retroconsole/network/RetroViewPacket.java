package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/** C2S: subscribe/unsubscribe to console frames at the given position. */
public record RetroViewPacket(
        BlockPos pos,
        boolean watching
) {

    public static final StreamCodec<ByteBuf, RetroViewPacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroViewPacket::pos,
                    ByteBufCodecs.BOOL,    RetroViewPacket::watching,
                    RetroViewPacket::new);
}
