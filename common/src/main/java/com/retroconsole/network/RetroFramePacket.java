package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import com.retroconsole.network.codec.StreamCodec;
import java.util.Arrays;
import java.util.UUID;
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
        UUID consoleId,
        int width,
        int height,
        byte[] compressedFrame
) {

    /** Sanity cap: frames larger than 4K are invalid packet data. */
    private static final int MAX_DIM = 4096;

    private static final ThreadLocal<Deflater> DEFLATER =
            ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));
    private static final ThreadLocal<Inflater> INFLATER =
            ThreadLocal.withInitial(Inflater::new);
    /** Per-thread RGB buffer: FrameSender compresses, network thread decompresses — no overlap. */
    private static final ThreadLocal<byte[]> RGB_BUF =
            ThreadLocal.withInitial(() -> new byte[0]);
    /** Per-thread deflate scratch — avoids a ByteArrayOutputStream (+ regrowth) per frame. */
    private static final ThreadLocal<byte[]> DEFLATE_OUT =
            ThreadLocal.withInitial(() -> new byte[0]);

    public static final StreamCodec<ByteBuf, RetroFramePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RetroFramePacket decode(ByteBuf buf) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                    UUID consoleId = fbb.readUUID();
                    int width = fbb.readVarInt();
                    int height = fbb.readVarInt();
                    if (width <= 0 || height <= 0 || width > MAX_DIM || height > MAX_DIM) {
                        throw new DecoderException("Bad frame size " + width + "x" + height);
                    }
                    int length = fbb.readVarInt();
                    // BUGFIX: previously new byte[length] without bounds — malicious or corrupt
                    // packets could request gigabyte allocations.
                    long max = (long) width * height * 3 + 1024;
                    if (length < 0 || length > max || length > fbb.readableBytes()) {
                        throw new DecoderException("Bad frame payload length " + length);
                    }
                    byte[] compressedFrame = new byte[length];
                    fbb.readBytes(compressedFrame);
                    return new RetroFramePacket(consoleId, width, height, compressedFrame);
                }

                @Override
                public void encode(ByteBuf buf, RetroFramePacket pkt) {
                    FriendlyByteBuf fbb = new FriendlyByteBuf(buf);
                    fbb.writeUUID(pkt.consoleId);
                    fbb.writeVarInt(pkt.width);
                    fbb.writeVarInt(pkt.height);
                    fbb.writeVarInt(pkt.compressedFrame.length);
                    fbb.writeBytes(pkt.compressedFrame);
                }
            };

    /**
     * Create a RetroFramePacket from an ARGB int[] frame buffer.
     * Strips alpha channel (RGB, 3 bytes/pixel) and compresses with Deflater at BEST_SPEED.
     */
    public static RetroFramePacket create(UUID consoleId, int[] frame, int width, int height) {
        int pixelCount = width * height;
        if (pixelCount <= 0 || frame.length < pixelCount) {
            return new RetroFramePacket(consoleId, width, height, new byte[0]);
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
        return new RetroFramePacket(consoleId, width, height, compressed);
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

    /**
     * Deflates {@code data} into a per-thread scratch buffer and returns an
     * exact-size copy — the packet byte[] must stay immutable because the
     * network layer encodes it later, on another thread. At 60 fps this saves
     * a 100-300 KB ByteArrayOutputStream + toByteArray() per frame.
     */
    private static byte[] compress(byte[] data, int len) {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        deflater.setInput(data, 0, len);
        deflater.finish();
        // zlib worst case on incompressible input is len + len/1000 + 6; grow on
        // demand so a pathological frame cannot truncate.
        byte[] out = DEFLATE_OUT.get();
        int cap = len + (len >> 10) + 64;
        if (out.length < cap) {
            out = new byte[cap];
            DEFLATE_OUT.set(out);
        }
        int written = 0;
        while (!deflater.finished()) {
            if (written == out.length) {
                out = Arrays.copyOf(out, Math.max(1024, out.length * 2));
                DEFLATE_OUT.set(out);
            }
            int n = deflater.deflate(out, written, out.length - written);
            if (n == 0) break; // needs input / no progress
            written += n;
        }
        return Arrays.copyOf(out, written);
    }
}