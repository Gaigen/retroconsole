package com.retroconsole.platform;

import com.retroconsole.network.payload.NetContext;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Adapter from NeoForge's payload context to the loader-neutral {@link NetContext}. */
public final class NeoContext implements NetContext {

    private final IPayloadContext context;

    public NeoContext(IPayloadContext context) {
        this.context = context;
    }

    @Override
    public Player player() {
        return context.player();
    }

    @Override
    public void enqueue(Runnable task) {
        context.enqueueWork(task);
    }
}
