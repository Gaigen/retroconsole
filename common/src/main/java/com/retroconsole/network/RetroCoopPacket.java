package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/** C2S: join or leave co-op as Player 2 (port 1) at the given console. */
public record RetroCoopPacket(
        BlockPos pos,
        boolean join
) {

    public static final StreamCodec<ByteBuf, RetroCoopPacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroCoopPacket::pos,
                    ByteBufCodecs.BOOL,     RetroCoopPacket::join,
                    RetroCoopPacket::new);
}
