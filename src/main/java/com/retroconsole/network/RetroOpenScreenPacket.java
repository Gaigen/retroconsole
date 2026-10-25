package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/** S2C: open the TV GUI (empty romId = game picker menu). */
public record RetroOpenScreenPacket(BlockPos pos, UUID consoleId, String romId, String systemId) {

    public RetroOpenScreenPacket(BlockPos pos, UUID consoleId, String romId) {
        this(pos, consoleId, romId, "");
    }

    public RetroOpenScreenPacket {
        if (systemId == null) systemId = "";
    }

    public static final StreamCodec<ByteBuf, RetroOpenScreenPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroOpenScreenPacket::pos,
                    RetroPackets.UUID_CODEC, RetroOpenScreenPacket::consoleId,
                    ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), RetroOpenScreenPacket::romId,
                    ByteBufCodecs.stringUtf8(16), RetroOpenScreenPacket::systemId,
                    RetroOpenScreenPacket::new);
}
