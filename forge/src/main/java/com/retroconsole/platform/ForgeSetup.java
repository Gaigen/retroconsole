package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import com.retroconsole.library.ArtFiles;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Forge glue for loader events that shared code cannot subscribe to.
 *
 * <p>{@code FMLCommonSetupEvent} fires after the config specs registered by
 * {@link RetroConsole} have been loaded — the first moment the configured
 * paths can be read (and the last moment the legacy toml values are still
 * available for the one-time import into {@code config/retroconsole.json}).
 * We enqueue the work onto the main thread so filesystem I/O never blocks the
 * setup pipeline.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, modid = RetroConsole.MOD_ID)
public final class ForgeSetup {

    private ForgeSetup() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ForgeConfig.install();
            // Console cover art is generated once, before anyone opens the library.
            ArtFiles.ensureDefaults(RetroConsolePaths.artDir());
            RetroConsolePaths.ensureAllExist();
        });
    }

    /**
     * The legacy {@code server} (and, on a client, {@code client}) config only
     * loads after common setup, so its values are handed to the import here.
     */
    @SubscribeEvent
    public static void onConfigLoaded(ModConfigEvent.Loading event) {
        if (RetroConsole.MOD_ID.equals(event.getConfig().getModId())) {
            ForgeConfig.onConfigLoaded(event.getConfig());
        }
    }
}
