package com.retroconsole.client;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Registers {@link ConfigScreen} as this mod's screen in the Mods menu.
 *
 * <p>This lives here, not in {@code RetroConsole}, because a lambda compiled into
 * the {@code @Mod} class would leave a synthetic method whose descriptor names
 * {@link Screen}. FML resolves every declared member of the mod class while
 * constructing the mod, so on a dedicated server that descriptor makes the dist
 * cleaner try to load a client-only class and the whole mod fails to load.
 */
public final class NeoForgeConfigScreen {

    private NeoForgeConfigScreen() {}

    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new ConfigScreen(parent));
    }
}
