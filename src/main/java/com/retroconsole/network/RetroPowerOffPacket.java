package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-bound: игрок нажал «Выкл» в TvScreen — выключить консоль.
 * Сервер останавливает эмуляцию через block entity (romId=""), что даёт
 * автосейв в ServerConsoles.stopEmulator() и RetroStopConsolePacket клиентам.
 */
public record RetroPowerOffPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RetroPowerOffPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "power_off"));

    public static final StreamCodec<FriendlyByteBuf, RetroPowerOffPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBlockPos(p.pos),
                    buf -> new RetroPowerOffPacket(buf.readBlockPos())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}