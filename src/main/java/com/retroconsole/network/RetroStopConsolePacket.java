package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** S2C: console stopped — reset video texture and OpenAL source. */
public record RetroStopConsolePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RetroStopConsolePacket> TYPE = RetroPackets.type("stop_console");

    public static final StreamCodec<ByteBuf, RetroStopConsolePacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(RetroStopConsolePacket::new, RetroStopConsolePacket::pos);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
