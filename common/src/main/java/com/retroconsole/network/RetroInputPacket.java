package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/** C2S: press/release a console button at the given position. */
public record RetroInputPacket(
        BlockPos pos,
        int buttonId,
        boolean pressed
) {

    public static final StreamCodec<ByteBuf, RetroInputPacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroInputPacket::pos,
                    ByteBufCodecs.VAR_INT,  RetroInputPacket::buttonId,
                    ByteBufCodecs.BOOL,     RetroInputPacket::pressed,
                    RetroInputPacket::new);
}
