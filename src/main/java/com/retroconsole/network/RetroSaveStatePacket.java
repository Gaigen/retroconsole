package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** F5/F6 — save/load слота состояния на сервере; auto — автосейв при выходе из TvScreen. */
public record RetroSaveStatePacket(
        BlockPos pos,
        int slot,
        boolean save,
        boolean auto
) implements CustomPacketPayload {

    public static final Type<RetroSaveStatePacket> TYPE = RetroPackets.type("save_state");

    public static final StreamCodec<ByteBuf, RetroSaveStatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroSaveStatePacket::pos,
                    ByteBufCodecs.VAR_INT, RetroSaveStatePacket::slot,
                    ByteBufCodecs.BOOL,    RetroSaveStatePacket::save,
                    ByteBufCodecs.BOOL,    RetroSaveStatePacket::auto,
                    RetroSaveStatePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
