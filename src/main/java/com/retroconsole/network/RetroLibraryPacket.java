package com.retroconsole.network;

import com.retroconsole.library.GameSystem;
import com.retroconsole.library.RomLibrary;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** S2C: server catalog of cores and ROMs (metadata only, no file contents). */
public record RetroLibraryPacket(
        BlockPos consolePos,
        List<SystemEntry> systems,
        List<CoreEntry> cores,
        List<RomEntry> roms,
        List<StatEntry> playerStats
) implements CustomPacketPayload {

    static final int MAX_CORES = 64;
    static final int MAX_ROMS = 4096;
    static final int MAX_SYSTEMS = 64;
    static final int MAX_STATS = 4096;

    public record SystemEntry(String id, String badge, String tab, String fullName, String folder,
                              int color, List<String> exts, List<String> coreHints, boolean custom) {
        static final StreamCodec<ByteBuf, List<String>> STR_LIST =
                ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.stringUtf8(32));

        /** composite() allows at most 6 fields; split across three 3-field sub-codecs. */
        private record SystemHead(String id, String badge, String tab) {
            static final StreamCodec<ByteBuf, SystemHead> CODEC = StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(32), SystemHead::id,
                    ByteBufCodecs.stringUtf8(16), SystemHead::badge,
                    ByteBufCodecs.stringUtf8(32), SystemHead::tab,
                    SystemHead::new);
        }

        private record SystemMid(String fullName, String folder, int color) {
            static final StreamCodec<ByteBuf, SystemMid> CODEC = StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), SystemMid::fullName,
                    ByteBufCodecs.stringUtf8(32), SystemMid::folder,
                    ByteBufCodecs.VAR_INT, SystemMid::color,
                    SystemMid::new);
        }

        private record SystemTail(List<String> exts, List<String> coreHints, boolean custom) {
            static final StreamCodec<ByteBuf, SystemTail> CODEC = StreamCodec.composite(
                    STR_LIST, SystemTail::exts,
                    STR_LIST, SystemTail::coreHints,
                    ByteBufCodecs.BOOL, SystemTail::custom,
                    SystemTail::new);
        }

        static final StreamCodec<ByteBuf, SystemEntry> CODEC = StreamCodec.composite(
                SystemHead.CODEC,
                e -> new SystemHead(e.id(), e.badge(), e.tab()),
                SystemMid.CODEC,
                e -> new SystemMid(e.fullName(), e.folder(), e.color()),
                SystemTail.CODEC,
                e -> new SystemTail(e.exts(), e.coreHints(), e.custom()),
                (head, mid, tail) -> new SystemEntry(
                        head.id(), head.badge(), head.tab(),
                        mid.fullName(), mid.folder(), mid.color(),
                        tail.exts(), tail.coreHints(), tail.custom()));
    }

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

    public record StatEntry(String romId, long playtimeSec, int launches, long lastPlayed) {
        static final StreamCodec<ByteBuf, StatEntry> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(RetroPackets.MAX_STR), StatEntry::romId,
                ByteBufCodecs.VAR_LONG, StatEntry::playtimeSec,
                ByteBufCodecs.VAR_INT, StatEntry::launches,
                ByteBufCodecs.VAR_LONG, StatEntry::lastPlayed,
                StatEntry::new);
    }

    public static final Type<RetroLibraryPacket> TYPE = RetroPackets.type("library");

    private static final StreamCodec<ByteBuf, List<SystemEntry>> SYSTEM_LIST =
            ByteBufCodecs.collection(ArrayList::new, SystemEntry.CODEC);

    private static final StreamCodec<ByteBuf, List<CoreEntry>> CORE_LIST =
            ByteBufCodecs.collection(ArrayList::new, CoreEntry.CODEC);

    private static final StreamCodec<ByteBuf, List<RomEntry>> ROM_LIST =
            ByteBufCodecs.collection(ArrayList::new, RomEntry.CODEC);

    private static final StreamCodec<ByteBuf, List<StatEntry>> STAT_LIST =
            ByteBufCodecs.collection(ArrayList::new, StatEntry.CODEC);

    public static final StreamCodec<ByteBuf, RetroLibraryPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RetroLibraryPacket::consolePos,
                    SYSTEM_LIST, RetroLibraryPacket::systems,
                    CORE_LIST, RetroLibraryPacket::cores,
                    ROM_LIST, RetroLibraryPacket::roms,
                    STAT_LIST, RetroLibraryPacket::playerStats,
                    RetroLibraryPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static RetroLibraryPacket from(BlockPos consolePos, RomLibrary lib,
                                          List<StatEntry> playerStats) {
        Set<String> systemIds = new HashSet<>();
        lib.cores.forEach(c -> systemIds.add(c.system().id));
        lib.roms.forEach(r -> systemIds.add(r.system().id));
        List<SystemEntry> systems = GameSystem.all().stream()
                .filter(s -> s != GameSystem.OTHER && systemIds.contains(s.id))
                .map(GameSystem::toEntry)
                .toList();
        List<CoreEntry> cores = lib.cores.stream()
                .map(c -> new CoreEntry(c.id(), c.system().id, c.displayName()))
                .toList();
        List<RomEntry> roms = lib.roms.stream()
                .map(r -> new RomEntry(r.id(), r.system().id, r.ext(), r.size(),
                        r.displayName(), r.misplaced()))
                .toList();
        return new RetroLibraryPacket(consolePos.immutable(), systems, cores, roms, playerStats);
    }

    /** Sanity check on decode (guards against oversized lists). */
    public RetroLibraryPacket {
        if (systems.size() > MAX_SYSTEMS || cores.size() > MAX_CORES || roms.size() > MAX_ROMS
                || playerStats.size() > MAX_STATS) {
            throw new DecoderException("Library too large: " + systems.size() + " systems, "
                    + cores.size() + " cores, " + roms.size() + " roms, "
                    + playerStats.size() + " stats");
        }
        systems = List.copyOf(systems);
        cores = List.copyOf(cores);
        roms = List.copyOf(roms);
        playerStats = List.copyOf(playerStats);
    }
}
