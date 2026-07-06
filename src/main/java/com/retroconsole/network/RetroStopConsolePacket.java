package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Уведомляет клиент об остановке консоли — сброс видео-текстуры и OpenAL-источника. */
public record RetroStopConsolePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RetroStopConsolePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "stop_console"));

    public static final StreamCodec<FriendlyByteBuf, RetroStopConsolePacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.pos),
                    buf -> new RetroStopConsolePacket(buf.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
