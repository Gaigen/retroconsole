package com.retroconsole.fabric.client;

import com.retroconsole.client.ClientAccess;
import com.retroconsole.client.ClientAudioHandler;
import com.retroconsole.client.ConfigScreen;
import com.retroconsole.client.GamepadScreen;
import com.retroconsole.config.ConfigAccess;
import com.retroconsole.fabric.FabricPayload;
import com.retroconsole.item.GamepadScreens;
import com.retroconsole.network.NetAccess;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Client-only facade wiring, the counterpart of {@code NeoForgeClientPlatform}.
 *
 * <p>{@link ClientPlayNetworking} is why the client-to-server sender is installed
 * here and not in the common entrypoint: naming it from the server path would
 * pull a client-only class into a dedicated server process.
 */
public final class FabricClientPlatform {

    private FabricClientPlatform() {}

    public static void init() {
        ConfigAccess.setScreenOpener((minecraft, parent) ->
                ConfigScreen.open((Minecraft) minecraft, (Screen) parent));

        GamepadScreens.setOpener(pos -> Minecraft.getInstance().setScreen(new GamepadScreen(pos)));

        NetAccess.setServerSender((definition, value) ->
                ClientPlayNetworking.send(FabricPayload.type(definition), FabricPayload.encode(definition, value)));

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
