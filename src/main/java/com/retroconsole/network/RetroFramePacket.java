package com.retroconsole.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Packet sent from server to client carrying a compressed emulator frame.
 * The frame is stored as compressed RGB bytes (3 bytes per pixel, no alpha).
 *
 * <p>BUGFIX (native memory leak): previously each frame created new Deflater()/Inflater()
 * without ever calling end(). DeflaterOutputStream.close() does not release the external
 * deflater, and finalization is lazy — at 60 fps native memory grew without showing in
 * heap dumps. Now: one Deflater/Inflater per thread (ThreadLocal) + reset() between frames.
 * Same for temporary byte[] buffers.
 *
 * <p>OPTIMIZATION: decompression outputs ABGR directly (GL RGBA little-endian) — no second
 * per-pixel pass in ClientConsoles.submitFrame.
 */
public record RetroFramePacket(
        BlockPos pos,
        int width,
        int height,
        byte[] compressedFrame
) implements CustomPacketPayload {

    public static final Type<RetroFramePacket> TYPE = RetroPackets.type("frame");

    /** Sanity cap: frames larger than 4K are invalid packet data. */
    private static final int MAX_DIM = 4096;

    private static final ThreadLocal<Deflater> DEFLATER =
            ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));
    private static final ThreadLocal<Inflater> INFLATER =
            ThreadLocal.withInitial(Inflater::new);
    /** Per-thread RGB buffer: FrameSender compresses, network thread decompresses — no overlap. */
    private static final ThreadLocal<byte[]> RGB_BUF =
            ThreadLocal.withInitial(() -> new byte[0]);
    private static final ThreadLocal<byte[]> CHUNK =
            ThreadLocal.withInitial(() -> new byte[64 * 1024]);

    public static final StreamCodec<FriendlyByteBuf, RetroFramePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroFramePacket decode(FriendlyByteBuf buf) {
                    BlockPos pos = buf.readBlockPos();
                    int width = buf.readVarInt();
                    int height = buf.readVarInt();
                    if (width <= 0 || height <= 0 || width > MAX_DIM || height > MAX_DIM) {
                        throw new DecoderException("Bad frame size " + width + "x" + height);
                    }
                    int length = buf.readVarInt();
                    // BUGFIX: previously new byte[length] without bounds — malicious or corrupt
                    // packets could request gigabyte allocations.
                    long max = (long) width * height * 3 + 1024;
                    if (length < 0 || length > max || length > buf.readableBytes()) {
                        throw new DecoderException("Bad frame payload length " + length);
                    }
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
     * Strips alpha channel (RGB, 3 bytes/pixel) and compresses with Deflater at BEST_SPEED.
     */
    public static RetroFramePacket create(BlockPos pos, int[] frame, int width, int height) {
        int pixelCount = width * height;
        if (pixelCount <= 0 || frame.length < pixelCount) {
            return new RetroFramePacket(pos, width, height, new byte[0]);
        }
        byte[] rgb = takeRgbBuf(pixelCount * 3);
        for (int i = 0; i < pixelCount; i++) {
            int argb = frame[i];
            int base = i * 3;
            rgb[base] = (byte) ((argb >> 16) & 0xFF);     // R
            rgb[base + 1] = (byte) ((argb >> 8) & 0xFF);  // G
            rgb[base + 2] = (byte) (argb & 0xFF);         // B
        }
        byte[] compressed = compress(rgb, pixelCount * 3);
        return new RetroFramePacket(pos, width, height, compressed);
    }

    /**
     * Decompress frame straight to ABGR (GL RGBA little-endian) for ClientConsoles.
     * Returns null on error. Missing pixels (mid-flight resolution change) are filled
     * opaque black. Inflate is capped at the expected frame size — no zip-bomb expansion.
     */
    public int[] decompressFrameAbgr() {
        int pixelCount = width * height;
        if (pixelCount <= 0) return null;
        int expected = pixelCount * 3;
        byte[] rgb = takeRgbBuf(expected);

        Inflater inflater = INFLATER.get();
        inflater.reset();
        inflater.setInput(compressedFrame);
        int got = 0;
        try {
            while (got < expected && !inflater.finished()) {
                int n = inflater.inflate(rgb, got, expected - got);
                if (n == 0) break; // needsInput / truncated stream
                got += n;
            }
        } catch (DataFormatException e) {
            return null;
        }

        int[] abgr = new int[pixelCount];
        int have = got / 3;
        int n = Math.min(pixelCount, have);
        for (int i = 0; i < n; i++) {
            int base = i * 3;
            int r = rgb[base] & 0xFF;
            int g = rgb[base + 1] & 0xFF;
            int b = rgb[base + 2] & 0xFF;
            abgr[i] = 0xFF000000 | (b << 16) | (g << 8) | r;
        }
        Arrays.fill(abgr, n, pixelCount, 0xFF000000);
        return abgr;
    }

    private static byte[] takeRgbBuf(int size) {
        byte[] b = RGB_BUF.get();
        if (b.length < size) {
            b = new byte[size];
            RGB_BUF.set(b);
        }
        return b;
    }

    private static byte[] compress(byte[] data, int len) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(data, 0, len);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(4096, len / 8));
        byte[] chunk = CHUNK.get();
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            if (n > 0) baos.write(chunk, 0, n);
        }
        return baos.toByteArray();
    }
}