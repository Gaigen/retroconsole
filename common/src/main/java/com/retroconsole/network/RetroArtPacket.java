package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import com.retroconsole.network.codec.ByteBufCodecs;
import com.retroconsole.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/** S2C: console art covers art/{folder}.png from server disk. */
public record RetroArtPacket(BlockPos consolePos, List<ArtEntry> images) {

    static final int MAX_IMAGES = 32;
    public static final int MAX_PNG_BYTES = 512 * 1024;

    public record ArtEntry(String folder, byte[] png) {
        static final StreamCodec<ByteBuf, ArtEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), ArtEntry::folder,
                ByteBufCodecs.byteArray(MAX_PNG_BYTES), ArtEntry::png,
                ArtEntry::new);
    }

    private static final StreamCodec<ByteBuf, List<ArtEntry>> IMAGE_LIST =
            ByteBufCodecs.collection(ArrayList::new, ArtEntry.CODEC);

    public static final StreamCodec<ByteBuf, RetroArtPacket> STREAM_CODEC =
            StreamCodec.composite(
                    com.retroconsole.network.codec.ByteBufCodecs.BLOCK_POS, RetroArtPacket::consolePos,
                    IMAGE_LIST, RetroArtPacket::images,
                    RetroArtPacket::new);

    public RetroArtPacket {
        if (images.size() > MAX_IMAGES) {
            throw new DecoderException("Too many art images: " + images.size());
        }
        images = List.copyOf(images);
    }
}
