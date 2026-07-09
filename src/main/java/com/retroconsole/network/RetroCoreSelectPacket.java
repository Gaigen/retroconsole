package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: select core + ROM for a console (starts the emulator). */
public record RetroCoreSelectPacket(
        BlockPos pos,
        String coreName,
        String romId,
        boolean loadAuto
) implements CustomPacketPayload {

    public static final Type<RetroCoreSelectPacket> TYPE = RetroPackets.type("core_select");

    public static final StreamCodec<ByteBuf, RetroCoreSelectPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,                          RetroCoreSelectPacket::pos,
                    ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), RetroCoreSelectPacket::coreName,
                    ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), RetroCoreSelectPacket::romId,
                    ByteBufCodecs.BOOL,                             RetroCoreSelectPacket::loadAuto,
                    RetroCoreSelectPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
