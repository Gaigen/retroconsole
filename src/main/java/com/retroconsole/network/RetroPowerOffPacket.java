package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: player pressed Power Off in TvScreen. Server stops emulation via
 * block entity (romId=""), which autosaves in ServerConsoles.stopEmulator()
 * and sends RetroStopConsolePacket to clients.
 */
public record RetroPowerOffPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RetroPowerOffPacket> TYPE = RetroPackets.type("power_off");

    public static final StreamCodec<ByteBuf, RetroPowerOffPacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(RetroPowerOffPacket::new, RetroPowerOffPacket::pos);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
