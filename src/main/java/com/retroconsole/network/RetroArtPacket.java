package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** S2C: console art covers art/{folder}.png from server disk. */
public record RetroArtPacket(BlockPos consolePos, List<ArtEntry> images) implements CustomPacketPayload {

    static final int MAX_IMAGES = 32;
    public static final int MAX_PNG_BYTES = 512 * 1024;

    public record ArtEntry(String folder, byte[] png) {
        static final StreamCodec<ByteBuf, ArtEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), ArtEntry::folder,
                ByteBufCodecs.byteArray(MAX_PNG_BYTES), ArtEntry::png,
                ArtEntry::new);
    }

    public static final Type<RetroArtPacket> TYPE = RetroPackets.type("art");

    private static final StreamCodec<ByteBuf, List<ArtEntry>> IMAGE_LIST =
            ByteBufCodecs.collection(ArrayList::new, ArtEntry.CODEC);

    public static final StreamCodec<ByteBuf, RetroArtPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroArtPacket::consolePos,
                    IMAGE_LIST, RetroArtPacket::images,
                    RetroArtPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public RetroArtPacket {
        if (images.size() > MAX_IMAGES) {
            throw new DecoderException("Too many art images: " + images.size());
        }
        images = List.copyOf(images);
    }
}
