package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from client to server carrying a button press/release event
 * for a retro console at a given block position.
 */
public record RetroInputPacket(
        BlockPos pos,
        int buttonId,
        boolean pressed
) implements CustomPacketPayload {

    public static final Type<RetroInputPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "input"));

    public static final StreamCodec<FriendlyByteBuf, RetroInputPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroInputPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    int buttonId = buf.readVarInt();
                    boolean pressed = buf.readBoolean();
                    return new RetroInputPacket(pos, buttonId, pressed);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RetroInputPacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                    buf.writeVarInt(pkt.buttonId);
                    buf.writeBoolean(pkt.pressed);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
