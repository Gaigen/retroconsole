package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: подписка/отписка на кадры консоли на заданной позиции. */
public record RetroViewPacket(
        BlockPos pos,
        boolean watching
) implements CustomPacketPayload {

    public static final Type<RetroViewPacket> TYPE = RetroPackets.type("view");

    public static final StreamCodec<ByteBuf, RetroViewPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroViewPacket::pos,
                    ByteBufCodecs.BOOL,    RetroViewPacket::watching,
                    RetroViewPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
