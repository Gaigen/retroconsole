package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.StreamCodec;

/**
 * C2S: player pressed Power Off in TvScreen. Server stops emulation via
 * block entity (romId=""), which autosaves in ServerConsoles.stopEmulator()
 * and sends RetroStopConsolePacket to clients.
 */
public record RetroPowerOffPacket(BlockPos pos) {

    public static final StreamCodec<ByteBuf, RetroPowerOffPacket> STREAM_CODEC =
            com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS.map(RetroPowerOffPacket::new, RetroPowerOffPacket::pos);
}
