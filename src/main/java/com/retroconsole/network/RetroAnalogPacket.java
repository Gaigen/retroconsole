package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S: полное аналоговое состояние геймпада (обе оси обоих стиков).
 * Один пакет вместо четырёх — меньше трафика. Клиент шлёт его только
 * при изменении состояния (с deadzone) и не чаще раза в тик.
 * Хендлер — в NetworkHandler.handleAnalog.
 */
public record RetroAnalogPacket(
        BlockPos pos,
        short lx,   // левый стик X, -32768..32767
        short ly,   // левый стик Y
        short rx,   // правый стик X
        short ry    // правый стик Y
) implements CustomPacketPayload {

    public static final Type<RetroAnalogPacket> TYPE = RetroPackets.type("analog");

    public static final StreamCodec<ByteBuf, RetroAnalogPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroAnalogPacket::pos,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::lx,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::ly,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::rx,
                    ByteBufCodecs.SHORT,   RetroAnalogPacket::ry,
                    RetroAnalogPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
