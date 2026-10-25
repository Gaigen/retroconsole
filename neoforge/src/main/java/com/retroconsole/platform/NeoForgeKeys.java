package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import com.retroconsole.client.ModKeys;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Registers the shared key list ({@link ModKeys#ALL}) with NeoForge.
 *
 * <p>Like every other loader hook here, it stays out of {@code common/} so that
 * module never has to name a loader API.
 */
@EventBusSubscriber(modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeKeys {

    private NeoForgeKeys() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : ModKeys.ALL) {
            event.register(mapping);
        }
    }
}
