package com.retroconsole.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * Packet sent from server to client carrying a compressed emulator frame.
 * The frame is stored as compressed RGB bytes (3 bytes per pixel, no alpha).
 */
public record RetroFramePacket(
        BlockPos pos,
        int width,
        int height,
        byte[] compressedFrame
) implements CustomPacketPayload {

    public static final Type<RetroFramePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("retroconsole", "frame"));

    public static final StreamCodec<FriendlyByteBuf, RetroFramePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroFramePacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    int width = buf.readVarInt();
                    int height = buf.readVarInt();
                    int length = buf.readVarInt();
                    byte[] compressedFrame = new byte[length];
                    buf.readBytes(compressedFrame);
                    return new RetroFramePacket(pos, width, height, compressedFrame);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RetroFramePacket pkt) {
                    buf.writeBlockPos(pkt.pos);
                    buf.writeVarInt(pkt.width);
                    buf.writeVarInt(pkt.height);
                    buf.writeVarInt(pkt.compressedFrame.length);
                    buf.writeBytes(pkt.compressedFrame);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Create a RetroFramePacket from an ARGB int[] frame buffer.
     * Strips alpha channel (converts to RGB, 3 bytes/pixel) and compresses with Deflater at BEST_SPEED.
     */
    public static RetroFramePacket create(BlockPos pos, int[] frame, int width, int height) {
        int pixelCount = width * height;
        if (pixelCount <= 0 || frame.length < pixelCount) {
            return new RetroFramePacket(pos, width, height, new byte[0]);
        }
        // Convert ARGB int[] to RGB byte[] (3 bytes per pixel)
        byte[] rgb = new byte[pixelCount * 3];
        for (int i = 0; i < pixelCount; i++) {
            int argb = frame[i];
            int base = i * 3;
            rgb[base]     = (byte) ((argb >> 16) & 0xFF); // R
            rgb[base + 1] = (byte) ((argb >> 8) & 0xFF);  // G
            rgb[base + 2] = (byte) (argb & 0xFF);          // B
        }

        byte[] compressed = compress(rgb);
        return new RetroFramePacket(pos, width, height, compressed);
    }

    /**
     * Decompress and convert back to an ARGB int[] frame buffer.
     * Returns null on error.
     *
     * <p>Note: the length of the decoded RGB byte stream is determined by
     * how many bytes the server compressed. If the core has changed its
     * resolution mid-flight, the wire length can be {@code != width*height*3}.
     * We clamp to whatever the decompressed buffer gives us and pad with
     * opaque black so a mismatch produces a black frame on the client
     * rather than a corrupted one or an exception.
     */
    public int[] decompressFrame() {
        byte[] rgb = decompress(compressedFrame);
        if (rgb == null) return null;

        int pixelCount = width * height;
        int[] argb = new int[pixelCount]; // zero-filled — bare pixels stay black (alpha=0)
        int have = rgb.length / 3;        // number of fully readable pixels
        int n = Math.min(pixelCount, have);
        for (int i = 0; i < n; i++) {
            int base = i * 3;
            int r = rgb[base] & 0xFF;
            int g = rgb[base + 1] & 0xFF;
            int b = rgb[base + 2] & 0xFF;
            argb[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return argb;
    }

    private static byte[] compress(byte[] data) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
                dos.write(data);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static byte[] decompress(byte[] compressed) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(compressed.length * 3);
            Inflater inflater = new Inflater();
            try (InflaterOutputStream ios = new InflaterOutputStream(baos, inflater)) {
                ios.write(compressed);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
