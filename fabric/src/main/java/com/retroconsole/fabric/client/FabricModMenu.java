package com.retroconsole.fabric.client;

import com.retroconsole.client.ConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional entrypoint for the Mod Menu mod, the counterpart of NeoForge's
 * {@code IConfigScreenFactory}: it puts {@link ConfigScreen} behind the Config
 * button in the mod list.
 *
 * <p>Fabric Loader has no mod list of its own, which is why this button only
 * exists once the player installs Mod Menu. The dependency is compile-only and
 * this class is named nowhere but in the {@code modmenu} entrypoint, so it is
 * loaded only when Mod Menu asks for it.
 */
public final class FabricModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new ConfigScreen(parent);
    }
}
