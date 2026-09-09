package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** S2C: console stopped — reset video texture and OpenAL source. */
public record RetroStopConsolePacket(UUID consoleId) implements CustomPacketPayload {

    public static final Type<RetroStopConsolePacket> TYPE = RetroPackets.type("stop_console");

    public static final StreamCodec<ByteBuf, RetroStopConsolePacket> STREAM_CODEC =
            RetroPackets.UUID_CODEC.map(RetroStopConsolePacket::new, RetroStopConsolePacket::consoleId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
