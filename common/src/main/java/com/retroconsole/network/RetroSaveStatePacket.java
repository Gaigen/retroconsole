package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

/** F5/F6 — save/load state slot on server; auto — autosave when leaving TvScreen. */
public record RetroSaveStatePacket(
        BlockPos pos,
        int slot,
        boolean save,
        boolean auto
) {

    public static final StreamCodec<ByteBuf, RetroSaveStatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroSaveStatePacket::pos,
                    ByteBufCodecs.VAR_INT, RetroSaveStatePacket::slot,
                    ByteBufCodecs.BOOL,    RetroSaveStatePacket::save,
                    ByteBufCodecs.BOOL,    RetroSaveStatePacket::auto,
                    RetroSaveStatePacket::new);
}
