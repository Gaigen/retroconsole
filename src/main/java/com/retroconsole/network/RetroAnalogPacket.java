package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Packet sent from client to server carrying analog stick state
 * for a retro console at a given block position.
 */
public record RetroAnalogPacket(
        BlockPos pos,
        int stick,  // 0=left, 1=right
        int axis,   // 0=X, 1=Y
        short value // -32768 to 32767
) implements CustomPacketPayload {

    public static final Type<RetroAnalogPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "analog"));

    public static final StreamCodec<FriendlyByteBuf, RetroAnalogPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroAnalogPacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    int stick = buf.readVarInt();
                    int axis = buf.readVarInt();
                    short value = buf.readShort();
                    return new RetroAnalogPacket(pos, stick, axis, value);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RetroAnalogPacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                    buf.writeVarInt(pkt.stick);
                    buf.writeVarInt(pkt.axis);
                    buf.writeShort(pkt.value);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
