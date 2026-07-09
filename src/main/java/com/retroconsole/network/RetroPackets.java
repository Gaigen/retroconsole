package com.retroconsole.network;

import com.retroconsole.RetroConsole;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Packet ID helper: avoids hardcoding "retroconsole" in every file. */
final class RetroPackets {

    private RetroPackets() {}

    /** Max string length in packets (coreName, romId). */
    static final int MAX_STR = 256;

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(RetroConsole.MOD_ID, path));
    }
}
