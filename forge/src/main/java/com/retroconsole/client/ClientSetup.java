package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import com.retroconsole.reg.ModBlockEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.EntityRenderersEvent;

/**
 * Client-side setup: registers block entity renderers.
 * Key mappings are registered by ModKeys via its own @SubscribeEvent.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.SCREEN_BE,
                ScreenBlockEntityRenderer::new
        );
    }
}
