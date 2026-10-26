package com.retroconsole.fabric;

import com.retroconsole.network.NetAccess;
import com.retroconsole.platform.RetroPaths;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;

/**
 * Fabric entrypoint (both sides).
 *
 * <p>Mirrors the NeoForge {@code @Mod} constructor: facades first, then payload
 * registration, then the blocks/items/block entities and the creative tab.
 * Everything client-only lives in {@code client/RetroConsoleFabricClient}, so a
 * dedicated server never touches a {@code net.minecraft.client} type from here.
 */
public class RetroConsoleFabric implements ModInitializer {

    public static final String MOD_ID = "retroconsole";

    private static final Logger BOOT_LOGGER = LoggerFactory.getLogger("RetroConsole-Boot");

    @Override
    public void onInitialize() {
        BOOT_LOGGER.info("JVM args: {}", ManagementFactory.getRuntimeMXBean().getInputArguments());

        // Filesystem roots and the unified config, before anything reads a path.
        RetroPaths.set(FabricPaths.INSTANCE);
        FabricConfig.install();

        // Server -> client delivery. The client -> server half is installed by the
        // client entrypoint, because ClientPlayNetworking is client-only.
        NetAccess.setPlayerSender((player, definition, value) ->
                ServerPlayNetworking.send(player, FabricPayload.create(definition, value)));

        FabricRegistries.register();
        FabricNetwork.registerCommon();
        FabricServerEvents.register();
    }
}
