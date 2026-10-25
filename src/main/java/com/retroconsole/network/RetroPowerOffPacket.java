package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

/**
 * C2S: player pressed Power Off in TvScreen. Server stops emulation via
 * block entity (romId=""), which autosaves in ServerConsoles.stopEmulator()
 * and sends RetroStopConsolePacket to clients.
 */
public record RetroPowerOffPacket(BlockPos pos) {

    public static final StreamCodec<ByteBuf, RetroPowerOffPacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(RetroPowerOffPacket::new, RetroPowerOffPacket::pos);
}
