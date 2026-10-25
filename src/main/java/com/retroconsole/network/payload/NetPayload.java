package com.retroconsole.network.payload;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Loader-agnostic description of a network payload: a stable id plus a codec.
 *
 * <p>The records in {@code com.retroconsole.network} stay plain data. Each loader
 * wraps this definition into its own payload type (NeoForge: {@code NeoPayload},
 * Fabric: {@code FabricPayload}) because the two loaders require incompatible
 * payload interfaces even though the wire format is identical.
 */
public interface NetPayload<T> {

    String id();

    /** Wire format. Plain {@code ByteBuf} so both loaders can use the same definition. */
    StreamCodec<ByteBuf, T> codec();

    static <T> NetPayload<T> of(String id, StreamCodec<ByteBuf, T> codec) {
        return new Definition<>(id, codec);
    }

    record Definition<T>(String id, StreamCodec<ByteBuf, T> codec) implements NetPayload<T> {}
}
