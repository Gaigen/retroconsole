package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: press/release a console button at the given position. */
public record RetroInputPacket(
        BlockPos pos,
        int buttonId,
        boolean pressed
) implements CustomPacketPayload {

    public static final Type<RetroInputPacket> TYPE = RetroPackets.type("input");

    public static final StreamCodec<ByteBuf, RetroInputPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroInputPacket::pos,
                    ByteBufCodecs.VAR_INT,  RetroInputPacket::buttonId,
                    ByteBufCodecs.BOOL,     RetroInputPacket::pressed,
                    RetroInputPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
