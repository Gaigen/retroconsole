package com.retroconsole.client;

import com.retroconsole.config.ConfigAccess;
import com.retroconsole.network.NetAccess;
import com.retroconsole.platform.NeoPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * NeoForge client-side facade wiring. Runs from {@link ClientLifecycle} on
 * FMLClientSetupEvent, i.e. only when a client is actually starting.
 */
public final class NeoForgeClientPlatform {

    private NeoForgeClientPlatform() {}

    public static void init() {
        NetAccess.setServerSender((definition, value) -> {
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new ServerboundCustomPayloadPacket(NeoPayload.create(definition, value)));
            }
        });
        ConfigAccess.setScreenOpener((minecraft, parent) ->
                ConfigScreen.open((Minecraft) minecraft, (Screen) parent));

        ClientAccess.setLifecycle(new ClientAccess.Lifecycle() {
            @Override
            public void onClientTick() {
                // No shared per-tick work yet; screens drive their own ticks.
            }

            @Override
            public void onPlayerJoin() {
                ClientAudioHandler.syncMuteToServer();
            }

            @Override
            public void onPlayerLeave() {
                ClientAudioHandler.stopAll();
            }

            @Override
            public void onRenderGui(float tickDelta) {
                // No global HUD overlay.
            }
        });
    }
}
