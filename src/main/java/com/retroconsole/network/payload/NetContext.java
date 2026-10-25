package com.retroconsole.network.payload;

import net.minecraft.world.entity.player.Player;

/**
 * The smallest slice of a loader's payload context that handlers need.
 * NeoForge adapts {@code IPayloadContext}, Fabric adapts its {@code Context}.
 */
public interface NetContext {

    /** The player this payload came from; the local player on the client side. */
    Player player();

    /** Run the task on the main game thread. */
    void enqueue(Runnable task);
}
