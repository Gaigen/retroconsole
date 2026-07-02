package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from client to server to select which core and ROM
 * to load on a retro console block.
 */
public record RetroCoreSelectPacket(
        BlockPos pos,
        String coreName,
        String romId
) implements CustomPacketPayload {

    public static final Type<RetroCoreSelectPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "core_select"));

    public static final StreamCodec<FriendlyByteBuf, RetroCoreSelectPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroCoreSelectPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    String coreName = buf.readUtf(256);
                    String romId = buf.readUtf(256);
                    return new RetroCoreSelectPacket(pos, coreName, romId);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RetroCoreSelectPacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                    buf.writeUtf(pkt.coreName, 256);
                    buf.writeUtf(pkt.romId, 256);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
