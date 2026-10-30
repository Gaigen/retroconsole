package com.retroconsole.platform;

import com.retroconsole.config.ConfigAccess;
import com.retroconsole.config.RetroSettings;
import com.retroconsole.network.NetAccess;
import net.minecraftforge.network.PacketDistributor;

/**
 * Forge implementation of the loader facades (server + common side).
 *
 * <p>Called once from the {@code @Mod} constructor, which runs on both the
 * client and the dedicated server. Client-only wiring (config screen, client
 * lifecycle) lives in {@code client/ForgeClientPlatform} so this class never
 * touches a client-only type.
 */
public final class ForgePlatform {

    private ForgePlatform() {}

    public static void init() {
        NetAccess.setPlayerSender((player, definition, value) ->
                ForgeNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ForgeNetwork.ToClient(definition, value)));
        NetAccess.setServerSender((definition, value) ->
                ForgeNetwork.CHANNEL.sendToServer(new ForgeNetwork.ToServer(definition, value)));

        // Config values and filesystem roots behind the loader-agnostic facades.
        RetroSettings.setSource(ForgeSettings.INSTANCE);
        RetroPaths.set(ForgePaths.INSTANCE);
        ConfigAccess.setReloader(ForgeConfig::reload);
    }
}
