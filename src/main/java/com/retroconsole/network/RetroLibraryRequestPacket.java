package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: запросить каталог ROM/ядер с сервера для консоли на заданной позиции. */
public record RetroLibraryRequestPacket(BlockPos consolePos) implements CustomPacketPayload {

    public static final Type<RetroLibraryRequestPacket> TYPE = RetroPackets.type("library_request");

    public static final StreamCodec<ByteBuf, RetroLibraryRequestPacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(RetroLibraryRequestPacket::new, RetroLibraryRequestPacket::consolePos);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
