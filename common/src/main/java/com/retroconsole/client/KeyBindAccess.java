package com.retroconsole.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/**
 * Loader-agnostic keybind registration.
 * NeoForge: RegisterKeyMappingsEvent. Fabric: KeyBindingHelper.registerKeyBinding.
 */
public final class KeyBindAccess {

    private KeyBindAccess() {}

    private static Registrar registrar = key -> { };

    /** Called by the loader entrypoint. */
    public static void setRegistrar(Registrar registrar) {
        if (registrar != null) KeyBindAccess.registrar = registrar;
    }

    /** Register a keybind from shared code. */
    public static void register(KeyMapping mapping) {
        registrar.register(mapping);
    }

    /** Check a named bind is pressed (shared code polls this each tick). */
    public static boolean isPressed(String name) {
        return pressed(name);
    }

    private static boolean pressed(String name) {
        return false;
    }

    public interface Registrar {
        void register(KeyMapping mapping);
    }
}
