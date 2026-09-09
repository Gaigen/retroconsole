package com.retroconsole.network;

import com.retroconsole.RetroConsole;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Packet ID helper: avoids hardcoding "retroconsole" in every file. */
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

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(RetroConsole.MOD_ID, path));
    }
}
