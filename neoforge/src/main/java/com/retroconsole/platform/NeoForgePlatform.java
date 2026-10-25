package com.retroconsole.platform;

import com.retroconsole.config.ConfigAccess;
import com.retroconsole.config.RetroSettings;
import com.retroconsole.network.NetAccess;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * NeoForge implementation of the loader facades (server + common side).
 *
 * <p>Called once from the {@code @Mod} constructor, which runs on both the
 * client and the dedicated server. Client-only wiring (config screen, client
 * lifecycle) lives in {@code client/NeoForgeClientPlatform} so this class never
 * touches a client-only type.
 */
public final class NeoForgePlatform {

    private NeoForgePlatform() {}

    public static void init() {
        NetAccess.setPlayerSender((player, definition, value) ->
                PacketDistributor.sendToPlayer(player, NeoPayload.create(definition, value)));
        NetAccess.setServerSender((definition, value) ->
                PacketDistributor.sendToServer(NeoPayload.create(definition, value)));

        // Config values and filesystem roots behind the loader-agnostic facades.
        RetroSettings.setSource(NeoForgeSettings.INSTANCE);
        RetroPaths.set(NeoForgePaths.INSTANCE);
        ConfigAccess.setReloader(NeoForgeConfig::reload);
    }
}
