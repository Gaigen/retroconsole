package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/**
 * C2S: raw keyboard press/release for a console.
 * {@code key} is a libretro RETROK code (SDL 1.2 values), see
 * {@code client.input.KeyboardMapper}.
 */
public record RetroKeyPacket(
        BlockPos pos,
        int key,
        boolean pressed
) {

    public static final StreamCodec<ByteBuf, RetroKeyPacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroKeyPacket::pos,
                    ByteBufCodecs.VAR_INT, RetroKeyPacket::key,
                    ByteBufCodecs.BOOL,    RetroKeyPacket::pressed,
                    RetroKeyPacket::new);
}
