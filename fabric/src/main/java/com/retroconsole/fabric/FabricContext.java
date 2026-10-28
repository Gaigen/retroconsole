package com.retroconsole.fabric;

import com.retroconsole.network.payload.NetContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.entity.player.Player;

/** Adapter from Fabric's server payload context to the loader-neutral {@link NetContext}. */
public final class FabricContext implements NetContext {

    private final ServerPlayNetworking.Context context;

    public FabricContext(ServerPlayNetworking.Context context) {
        this.context = context;
    }

    @Override
    public Player player() {
        return context.player();
    }

    @Override
    public void enqueue(Runnable task) {
        context.server().execute(task);
    }
}
