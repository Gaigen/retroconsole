package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/**
 * C2S: full gamepad analog state (both axes of both sticks).
 * One packet instead of four — less traffic. Client sends only on change
 * (with deadzone) and at most once per tick. Handler: ServerPayloadHandlers.handleAnalog.
 */
public record RetroAnalogPacket(
        BlockPos pos,
        short lx,   // left stick X, -32768..32767
        short ly,   // left stick Y
        short rx,   // right stick X
        short ry    // right stick Y
) {

    public static final StreamCodec<ByteBuf, RetroAnalogPacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroAnalogPacket::pos,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::lx,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::ly,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::rx,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::ry,
                    RetroAnalogPacket::new);
}
