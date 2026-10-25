package com.retroconsole.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/** Shared helpers for the packet records: length caps and the UUID codec. */
final class RetroPackets {

    private RetroPackets() {}

    /** Max string length in packets (coreName, romId). */
    static final int MAX_STR = 256;

    /** ByteBufCodecs.UUID is unavailable on 1.21.1 — use FriendlyByteBuf helpers. */
    static final StreamCodec<ByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
        @Override
        public UUID decode(ByteBuf buf) {
            return new FriendlyByteBuf(buf).readUUID();
        }

        @Override
        public void encode(ByteBuf buf, UUID value) {
            new FriendlyByteBuf(buf).writeUUID(value);
        }
    };
}
