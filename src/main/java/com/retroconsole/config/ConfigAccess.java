package com.retroconsole.config;

/**
 * Loader-agnostic config screen opening.
 * NeoForge: ConfigurationScreen via IConfigScreenFactory.
 * Fabric: Cloth Config / YACL screen.
 */
public final class ConfigAccess {

    private ConfigAccess() {}

    private static ScreenOpener opener = (minecraft, parent) -> { };
    private static Runnable reloader = () -> { };

    /** Called by the loader entrypoint (client side). */
    public static void setScreenOpener(ScreenOpener opener) {
        if (opener != null) ConfigAccess.opener = opener;
    }

    /** Called by the loader entrypoint. */
    public static void setReloader(Runnable reloader) {
        if (reloader != null) ConfigAccess.reloader = reloader;
    }

    /** Open the in-game config screen. minecraft/parent are loader Screen objects. */
    public static void openScreen(Object minecraft, Object parent) {
        opener.open(minecraft, parent);
    }

    public static void reload() {
        reloader.run();
    }

    public interface ScreenOpener {
        void open(Object minecraft, Object parent);
    }
}
