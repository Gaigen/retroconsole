package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import com.retroconsole.client.ModKeys;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;

/**
 * Registers the shared key list ({@link ModKeys#ALL}) with Forge.
 *
 * <p>Like every other loader hook here, it stays out of {@code common/} so that
 * module never has to name a loader API.
 */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public final class ForgeKeys {

    private ForgeKeys() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        for (KeyMapping mapping : ModKeys.ALL) {
            event.register(mapping);
        }
    }
}
