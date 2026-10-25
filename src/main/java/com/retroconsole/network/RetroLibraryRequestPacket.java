package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

/** C2S: request the ROM/core catalog from the server for a console at the given position. */
public record RetroLibraryRequestPacket(BlockPos consolePos) {

    public static final StreamCodec<ByteBuf, RetroLibraryRequestPacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(RetroLibraryRequestPacket::new, RetroLibraryRequestPacket::consolePos);
}
