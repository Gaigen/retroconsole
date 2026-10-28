package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import com.retroconsole.network.payload.NetPayload;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge payload box: the single {@link CustomPacketPayload} implementation the
 * mod registers. One type per {@link NetPayload} definition, created from its id,
 * so adding a packet never means writing another payload class.
 */
public record NeoPayload<T>(
        NetPayload<T> definition,
        T value,
        CustomPacketPayload.Type<NeoPayload<T>> id
) implements CustomPacketPayload {

    private static final Map<String, CustomPacketPayload.Type<?>> TYPES = new ConcurrentHashMap<>();

    /** Cached per id: the registrar keys on the Type, so the instance must stay stable. */
    @SuppressWarnings("unchecked")
    public static <T> CustomPacketPayload.Type<NeoPayload<T>> type(NetPayload<T> definition) {
        return (CustomPacketPayload.Type<NeoPayload<T>>) TYPES.computeIfAbsent(definition.id(),
                id -> new CustomPacketPayload.Type<NeoPayload<?>>(
                        Identifier.fromNamespaceAndPath(RetroConsole.MOD_ID, id)));
    }

    public static <T> NeoPayload<T> of(NetPayload<T> definition, T value) {
        return new NeoPayload<>(definition, value, type(definition));
    }

    /** Untyped variant for the {@code Object} boundary of {@code NetAccess}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CustomPacketPayload create(NetPayload<?> definition, Object value) {
        return of((NetPayload) definition, value);
    }

    /** Wire format: identical to the definition's codec, just boxed. */
    public static <T> StreamCodec<ByteBuf, NeoPayload<T>> codec(NetPayload<T> definition) {
        return definition.codec().map(v -> of(definition, v), NeoPayload::value);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return id;
    }
}
