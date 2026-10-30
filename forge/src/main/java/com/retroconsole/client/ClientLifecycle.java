package com.retroconsole.client;

import com.retroconsole.RetroConsole;
import com.retroconsole.item.GamepadScreens;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD, modid = RetroConsole.MOD_ID, value = Dist.CLIENT)
public class ClientLifecycle {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ForgeClientPlatform.init();
        GamepadScreens.setOpener(pos -> Minecraft.getInstance().setScreen(new GamepadScreen(pos)));
    }

}
