package com.retroconsole.client;

/**
 * Loader-agnostic client lifecycle hooks.
 * NeoForge: @EventBusSubscriber(Dist.CLIENT) events.
 * Fabric: ClientTickEvents / ClientPlayConnectionEvents.
 */
public final class ClientAccess {

    private ClientAccess() {}

    private static Lifecycle lifecycle = new Lifecycle() {
        public void onClientTick() { }
        public void onPlayerJoin() { }
        public void onPlayerLeave() { }
        public void onRenderGui(float tickDelta) { }
    };

    /** Called by the loader entrypoint. */
    public static void setLifecycle(Lifecycle lifecycle) {
        if (lifecycle != null) ClientAccess.lifecycle = lifecycle;
    }

    /** Shared code calls these; loader implementation dispatches to real events. */
    public static void fireClientTick() {
        lifecycle.onClientTick();
    }

    public static void firePlayerJoin() {
        lifecycle.onPlayerJoin();
    }

    public static void firePlayerLeave() {
        lifecycle.onPlayerLeave();
    }

    public static void fireRenderGui(float tickDelta) {
        lifecycle.onRenderGui(tickDelta);
    }

    public interface Lifecycle {
        void onClientTick();
        void onPlayerJoin();
        void onPlayerLeave();
        void onRenderGui(float tickDelta);
    }
}
