package com.retroconsole.network.payload;

/** A payload handler, decoupled from any loader's context type. */
@FunctionalInterface
public interface NetHandler<T> {
    void handle(T value, NetContext context);
}
