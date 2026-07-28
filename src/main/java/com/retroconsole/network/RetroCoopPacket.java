package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: join or leave co-op as Player 2 (port 1) at the given console. */
public record RetroCoopPacket(
        BlockPos pos,
        boolean join
) implements CustomPacketPayload {

    public static final Type<RetroCoopPacket> TYPE = RetroPackets.type("coop");

    public static final StreamCodec<ByteBuf, RetroCoopPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroCoopPacket::pos,
                    ByteBufCodecs.BOOL,     RetroCoopPacket::join,
                    RetroCoopPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
