package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: full gamepad analog state (both axes of both sticks).
 * One packet instead of four — less traffic. Client sends only on change
 * (with deadzone) and at most once per tick. Handler: NetworkHandler.handleAnalog.
 */
public record RetroAnalogPacket(
        BlockPos pos,
        short lx,   // left stick X, -32768..32767
        short ly,   // left stick Y
        short rx,   // right stick X
        short ry    // right stick Y
) implements CustomPacketPayload {

    public static final Type<RetroAnalogPacket> TYPE = RetroPackets.type("analog");

    public static final StreamCodec<ByteBuf, RetroAnalogPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroAnalogPacket::pos,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::lx,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::ly,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::rx,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::ry,
                    RetroAnalogPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
