package com.retroconsole.fabric.client;

import com.retroconsole.client.ClientAccess;
import com.retroconsole.client.ModKeys;
import com.retroconsole.client.ScreenBlockEntityRenderer;
import com.retroconsole.reg.ModBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;

/**
 * Fabric client entrypoint, the counterpart of the NeoForge
 * {@code ClientLifecycle} + {@code ClientSetup} + {@code NeoForgeKeys} trio.
 */
public final class RetroConsoleFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FabricClientPlatform.init();
        registerKeys();
        FabricClientNetwork.register();

        BlockEntityRendererRegistry.register(ModBlockEntities.SCREEN_BE, ScreenBlockEntityRenderer::new);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientAccess.firePlayerJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientAccess.firePlayerLeave());
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientAccess.fireClientTick());
    }

    private void registerKeys() {
        for (KeyMapping mapping : ModKeys.ALL) {
            KeyBindingHelper.registerKeyBinding(mapping);
        }
    }
}
