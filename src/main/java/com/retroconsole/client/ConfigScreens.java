package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Opens NeoForge's auto config UI for this mod (Mods menu + in-library gear). */
public final class ConfigScreens {

    private ConfigScreens() {}

    public static void open(Screen parent) {
        ModList.get().getModContainerById(RetroConsole.MOD_ID).ifPresent(container -> {
            Screen screen = IConfigScreenFactory.getForMod(container.getModInfo())
                    .map(factory -> factory.createScreen(container, parent))
                    .orElseGet(() -> new ConfigurationScreen(container, parent));
            Minecraft.getInstance().setScreen(screen);
        });
    }
}
