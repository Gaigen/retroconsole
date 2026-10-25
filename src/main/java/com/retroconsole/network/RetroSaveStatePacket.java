package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** F5/F6 — save/load state slot on server; auto — autosave when leaving TvScreen. */
public record RetroSaveStatePacket(
        BlockPos pos,
        int slot,
        boolean save,
        boolean auto
) {

    public static final StreamCodec<ByteBuf, RetroSaveStatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroSaveStatePacket::pos,
                    ByteBufCodecs.VAR_INT, RetroSaveStatePacket::slot,
                    ByteBufCodecs.BOOL,    RetroSaveStatePacket::save,
                    ByteBufCodecs.BOOL,    RetroSaveStatePacket::auto,
                    RetroSaveStatePacket::new);
}
