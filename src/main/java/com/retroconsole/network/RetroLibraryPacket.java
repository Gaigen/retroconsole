package com.retroconsole.network;

import com.retroconsole.library.RomLibrary;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** S2C: каталог ядер и ROM с сервера (метаданные, без содержимого файлов). */
public record RetroLibraryPacket(
        BlockPos consolePos,
        List<CoreEntry> cores,
        List<RomEntry> roms
) implements CustomPacketPayload {

    static final int MAX_CORES = 64;
    static final int MAX_ROMS = 4096;

    public record CoreEntry(String id, String systemId, String displayName) {
        static final StreamCodec<ByteBuf, CoreEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), CoreEntry::id,
                ByteBufCodecs.stringUtf8(32), CoreEntry::systemId,
                ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), CoreEntry::displayName,
                CoreEntry::new);
    }

    public record RomEntry(String id, String systemId, String ext, long size,
                           String displayName, boolean misplaced) {
        static final StreamCodec<ByteBuf, RomEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), RomEntry::id,
                ByteBufCodecs.stringUtf8(32), RomEntry::systemId,
                ByteBufCodecs.stringUtf8(16), RomEntry::ext,
                ByteBufCodecs.VAR_LONG, RomEntry::size,
                ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), RomEntry::displayName,
                ByteBufCodecs.BOOL, RomEntry::misplaced,
                RomEntry::new);
    }

    public static final Type<RetroLibraryPacket> TYPE = RetroPackets.type("library");

    private static final StreamCodec<ByteBuf, List<CoreEntry>> CORE_LIST =
            ByteBufCodecs.collection(ArrayList::new, CoreEntry.CODEC);

    private static final StreamCodec<ByteBuf, List<RomEntry>> ROM_LIST =
            ByteBufCodecs.collection(ArrayList::new, RomEntry.CODEC);

    public static final StreamCodec<ByteBuf, RetroLibraryPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroLibraryPacket::consolePos,
                    CORE_LIST, RetroLibraryPacket::cores,
                    ROM_LIST, RetroLibraryPacket::roms,
                    RetroLibraryPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static RetroLibraryPacket from(BlockPos consolePos, RomLibrary lib) {
        List<CoreEntry> cores = lib.cores.stream()
                .map(c -> new CoreEntry(c.id(), c.system().id, c.displayName()))
                .toList();
        List<RomEntry> roms = lib.roms.stream()
                .map(r -> new RomEntry(r.id(), r.system().id, r.ext(), r.size(),
                        r.displayName(), r.misplaced()))
                .toList();
        return new RetroLibraryPacket(consolePos.immutable(), cores, roms);
    }

    /** Санити-проверка при decode (защита от огромных списков). */
    public RetroLibraryPacket {
        if (cores.size() > MAX_CORES || roms.size() > MAX_ROMS) {
            throw new DecoderException("Library too large: " + cores.size() + " cores, " + roms.size() + " roms");
        }
        cores = List.copyOf(cores);
        roms = List.copyOf(roms);
    }
}
