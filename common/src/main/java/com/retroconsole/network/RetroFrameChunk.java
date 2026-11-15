package com.retroconsole.network;

import com.retroconsole.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** A portion of one compressed video frame. */
public record RetroFrameChunk(UUID consoleId, long frameId, int width, int height,
                              int totalBytes, int index, byte[] data) {
    public static final int CHUNK_BYTES = 512 * 1024;
    public static final int MAX_DIM = 4096;
    public static final int MAX_FRAME_BYTES = MAX_DIM * MAX_DIM * 3 + 65536;

    public RetroFrameChunk {
        long raw = (long) width * height * 3;
        if (consoleId == null || width <= 0 || height <= 0 || width > MAX_DIM || height > MAX_DIM
                || totalBytes <= 0 || totalBytes > MAX_FRAME_BYTES || totalBytes > raw + (raw >> 10) + 1024
                || index < 0 || index >= (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES
                || data == null || data.length != Math.min(CHUNK_BYTES, totalBytes - index * CHUNK_BYTES))
            throw new DecoderException("Invalid video frame chunk");
    }

    public static final StreamCodec<ByteBuf, RetroFrameChunk> STREAM_CODEC = new StreamCodec<>() {
        public RetroFrameChunk decode(ByteBuf buf) {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            UUID id = b.readUUID(); long frameId = b.readLong();
            int width = b.readVarInt(), height = b.readVarInt(), total = b.readVarInt(), index = b.readVarInt();
            int length = b.readVarInt();
            if (length < 0 || length > CHUNK_BYTES || length > b.readableBytes())
                throw new DecoderException("Invalid video chunk length " + length);
            byte[] data = new byte[length]; b.readBytes(data);
            return new RetroFrameChunk(id, frameId, width, height, total, index, data);
        }
        public void encode(ByteBuf buf, RetroFrameChunk c) {
            FriendlyByteBuf b = new FriendlyByteBuf(buf);
            b.writeUUID(c.consoleId); b.writeLong(c.frameId); b.writeVarInt(c.width); b.writeVarInt(c.height);
            b.writeVarInt(c.totalBytes); b.writeVarInt(c.index); b.writeVarInt(c.data.length); b.writeBytes(c.data);
        }
    };

    public static List<RetroFrameChunk> split(RetroFramePacket frame, long frameId) {
        byte[] bytes = frame.compressedFrame();
        List<RetroFrameChunk> chunks = new ArrayList<>((bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        for (int offset = 0, index = 0; offset < bytes.length; offset += CHUNK_BYTES, index++)
            chunks.add(new RetroFrameChunk(frame.consoleId(), frameId, frame.width(), frame.height(), bytes.length,
                    index, Arrays.copyOfRange(bytes, offset, Math.min(offset + CHUNK_BYTES, bytes.length))));
        return chunks;
    }
}
