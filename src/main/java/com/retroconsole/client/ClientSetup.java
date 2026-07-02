package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import com.retroconsole.reg.ModBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-side setup: registers block entity renderers.
 * Key mappings are registered by ModKeys via its own @SubscribeEvent.
 */
@EventBusSubscriber(modid = RetroConsole.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.SCREEN_BE.get(),
                ScreenBlockEntityRenderer::new
        );
    }
}
