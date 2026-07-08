package com.retroconsole.network;

import com.retroconsole.RetroConsole;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Хелпер для ID пакетов: убирает хардкод "retroconsole" из каждого файла. */
final class RetroPackets {

    private RetroPackets() {}

    /** Максимальная длина строк в пакетах (coreName, romId). */
    static final int MAX_STR = 256;

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(RetroConsole.MOD_ID, path));
    }
}
