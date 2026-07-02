package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from server to client to open the TV screen GUI.
 */
public record RetroOpenScreenPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<RetroOpenScreenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "open_screen"));

    public static final StreamCodec<FriendlyByteBuf, RetroOpenScreenPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroOpenScreenPacket decode(FriendlyByteBuf buf) {
                    return new RetroOpenScreenPacket(buf.readBlockPos());
                }

                @Override
                public void encode(FriendlyByteBuf buf, RetroOpenScreenPacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
