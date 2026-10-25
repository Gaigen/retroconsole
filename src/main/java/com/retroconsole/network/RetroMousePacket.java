package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: relative mouse state for a console (Doom 3 and other keyboard+mouse cores).
 *
 * @param dx      horizontal movement since the previous packet
 * @param dy      vertical movement since the previous packet
 * @param buttons bit mask: 1 left, 2 right, 4 middle, 8 back (button 4), 16 forward (button 5)
 * @param wheel   wheel detents since the previous packet, positive = up
 */
public record RetroMousePacket(
        BlockPos pos,
        int dx,
        int dy,
        int buttons,
        int wheel
) implements CustomPacketPayload {

    public static final Type<RetroMousePacket> TYPE = RetroPackets.type("mouse");

    public static final StreamCodec<ByteBuf, RetroMousePacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroMousePacket::pos,
                    ByteBufCodecs.VAR_INT, RetroMousePacket::dx,
                    ByteBufCodecs.VAR_INT, RetroMousePacket::dy,
                    ByteBufCodecs.VAR_INT, RetroMousePacket::buttons,
                    ByteBufCodecs.VAR_INT, RetroMousePacket::wheel,
                    RetroMousePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
