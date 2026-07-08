package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: игрок нажал «Выкл» в TvScreen. Сервер останавливает эмуляцию через
 * block entity (romId=""), что даёт автосейв в ServerConsoles.stopEmulator()
 * и RetroStopConsolePacket клиентам.
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
