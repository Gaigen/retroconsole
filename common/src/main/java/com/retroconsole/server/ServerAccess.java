package com.retroconsole.server;

import net.minecraft.server.MinecraftServer;

/**
 * Loader-agnostic access to the running server. Each loader sets it at startup:
 * NeoForge from ServerStartedEvent/ServerStoppingEvent, Fabric from
 * SERVER_STARTED/SERVER_STOPPING.
 */
public final class ServerAccess {

    private static MinecraftServer currentServer;

    private ServerAccess() {}

    /** Called by the loader entrypoint (both sides). */
    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }
}
