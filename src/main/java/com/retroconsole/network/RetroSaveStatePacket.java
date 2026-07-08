package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** F5/F6 — save or load emulator state slot on the server; {@code auto} — автосейв при выходе из TvScreen. */
public record RetroSaveStatePacket(
        BlockPos pos,
        int slot,
        boolean save,
        boolean auto
) implements CustomPacketPayload {

    public static final Type<RetroSaveStatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "save_state"));

    public static final StreamCodec<FriendlyByteBuf, RetroSaveStatePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroSaveStatePacket decode(FriendlyByteBuf buf) {
                    return new RetroSaveStatePacket(
                            buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
                }

                @Override
                public void encode(FriendlyByteBuf buf, RetroSaveStatePacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                    buf.writeVarInt(pkt.slot);
                    buf.writeBoolean(pkt.save);
                    buf.writeBoolean(pkt.auto);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
