package com.retroconsole.fabric;

import com.retroconsole.network.payload.NetPayload;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric payload box: the single {@link CustomPacketPayload} implementation the
 * mod registers, one type per {@link NetPayload} definition.
 *
 * <p>Fabric requires its own payload type, the same way NeoForge does, which is
 * why shared code only ever names a {@code NetPayload} constant.
 */
public record FabricPayload<T>(
        NetPayload<T> definition,
        T value,
        CustomPacketPayload.Type<FabricPayload<T>> id
) implements CustomPacketPayload {

    private static final Map<String, CustomPacketPayload.Type<?>> TYPES = new ConcurrentHashMap<>();

    /** Cached per id: the registries key on the type, so it has to stay stable. */
    @SuppressWarnings("unchecked")
    public static <T> CustomPacketPayload.Type<FabricPayload<T>> type(NetPayload<T> definition) {
        return (CustomPacketPayload.Type<FabricPayload<T>>) TYPES.computeIfAbsent(definition.id(),
                id -> new CustomPacketPayload.Type<FabricPayload<?>>(
                        ResourceLocation.fromNamespaceAndPath(RetroConsoleFabric.MOD_ID, id)));
    }

    public static <T> FabricPayload<T> of(NetPayload<T> definition, T value) {
        return new FabricPayload<>(definition, value, type(definition));
    }

    /** Untyped variant for the {@code Object} boundary of {@code NetAccess}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CustomPacketPayload create(NetPayload<?> definition, Object value) {
        return of((NetPayload) definition, value);
    }

    /**
     * Fabric registers payload codecs against {@code RegistryFriendlyByteBuf},
     * while the shared definitions describe their wire format over a plain
     * {@code ByteBuf}; this adapter bridges the two without copying bytes.
     */
    public static <T> StreamCodec<RegistryFriendlyByteBuf, FabricPayload<T>> codec(NetPayload<T> definition) {
        StreamCodec<? super RegistryFriendlyByteBuf, T> inner = definition.codec();
        return new StreamCodec<>() {
            @Override
            public FabricPayload<T> decode(RegistryFriendlyByteBuf buf) {
                return of(definition, inner.decode(buf));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buf, FabricPayload<T> payload) {
                inner.encode(buf, payload.value());
            }
        };
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return id;
    }
}
