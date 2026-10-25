package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * NeoForge glue for loader events that shared code cannot subscribe to.
 *
 * <p>{@code FMLCommonSetupEvent} fires after the config specs registered by
 * {@link RetroConsole} have been loaded — the first moment the configured
 * paths can be read (and the last moment the legacy toml values are still
 * available for the one-time import into {@code config/retroconsole.json}).
 * We enqueue the work onto the main thread so filesystem I/O never blocks the
 * setup pipeline.
 */
@EventBusSubscriber(modid = RetroConsole.MOD_ID)
public final class NeoForgeSetup {

    private NeoForgeSetup() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            NeoForgeConfig.install();
            RetroConsolePaths.ensureAllExist();
        });
    }

    /**
     * The legacy {@code server} (and, on a client, {@code client}) config only
     * loads after common setup, so its values are handed to the import here.
     */
    @SubscribeEvent
    public static void onConfigLoaded(ModConfigEvent.Loading event) {
        NeoForgeConfig.onConfigLoaded(event.getConfig());
    }
}
