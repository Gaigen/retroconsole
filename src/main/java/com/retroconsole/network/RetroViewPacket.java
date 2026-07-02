package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from client to server to subscribe or unsubscribe
 * from frame updates for a retro console at a given block position.
 */
public record RetroViewPacket(
        BlockPos pos,
        boolean watching
) implements CustomPacketPayload {

    public static final Type<RetroViewPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "view"));

    public static final StreamCodec<FriendlyByteBuf, RetroViewPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroViewPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    boolean watching = buf.readBoolean();
                    return new RetroViewPacket(pos, watching);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RetroViewPacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                    buf.writeBoolean(pkt.watching);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
