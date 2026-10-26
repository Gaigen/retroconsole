package com.retroconsole.fabric.client;

import com.retroconsole.network.payload.NetContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.player.Player;

/** Adapter from Fabric's client payload context to the loader-neutral {@link NetContext}. */
public final class FabricClientContext implements NetContext {

    private final ClientPlayNetworking.Context context;

    public FabricClientContext(ClientPlayNetworking.Context context) {
        this.context = context;
    }

    @Override
    public Player player() {
        return context.player();
    }

    @Override
    public void enqueue(Runnable task) {
        context.client().execute(task);
    }
}
