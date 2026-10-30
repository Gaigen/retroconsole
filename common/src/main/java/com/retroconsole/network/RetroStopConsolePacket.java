package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

import java.util.UUID;

/** S2C: console stopped — reset video texture and OpenAL source. */
public record RetroStopConsolePacket(UUID consoleId) {

    public static final StreamCodec<ByteBuf, RetroStopConsolePacket> STREAM_CODEC =
            RetroPackets.UUID_CODEC.map(RetroStopConsolePacket::new, RetroStopConsolePacket::consoleId);
}
