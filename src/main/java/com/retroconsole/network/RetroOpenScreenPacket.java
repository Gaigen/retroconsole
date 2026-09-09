package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** S2C: open the TV GUI (empty romId = game picker menu). */
public record RetroOpenScreenPacket(BlockPos pos, UUID consoleId, String romId, String systemId)
        implements CustomPacketPayload {

    public RetroOpenScreenPacket(BlockPos pos, UUID consoleId, String romId) {
        this(pos, consoleId, romId, "");
    }

    public RetroOpenScreenPacket {
        if (systemId == null) systemId = "";
    }

    public static final Type<RetroOpenScreenPacket> TYPE = RetroPackets.type("open_screen");

    public static final StreamCodec<ByteBuf, RetroOpenScreenPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroOpenScreenPacket::pos,
                    RetroPackets.UUID_CODEC, RetroOpenScreenPacket::consoleId,
                    ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), RetroOpenScreenPacket::romId,
                    ByteBufCodecs.stringUtf8(16), RetroOpenScreenPacket::systemId,
                    RetroOpenScreenPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
