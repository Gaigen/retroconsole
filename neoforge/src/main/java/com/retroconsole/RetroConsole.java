package com.retroconsole;

import com.retroconsole.client.NeoForgeConfigScreen;
import com.retroconsole.config.ModConfig;
import com.retroconsole.gametest.RetroConsoleGameTests;
import com.retroconsole.platform.NeoForgeConfig;
import com.retroconsole.platform.NeoForgePlatform;
import com.retroconsole.platform.NeoForgeRegistries;
import com.retroconsole.reg.ModBlocks;
import com.retroconsole.reg.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.function.Supplier;

@Mod(RetroConsole.MOD_ID)
public class RetroConsole {

    private static final Logger BOOT_LOGGER = LoggerFactory.getLogger("RetroConsole-Boot");

    public static final String MOD_ID = "retroconsole";

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    public static final Supplier<CreativeModeTab> RETROCONSOLE_TAB = CREATIVE_TABS.register(
            "retroconsole_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.retroconsole"))
                    .icon(() -> new ItemStack(ModBlocks.RETRO_CONSOLE))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.RETRO_CONSOLE_ITEM);
                        output.accept(ModBlocks.SCREEN_ITEM);
                        output.accept(ModItems.GAMEPAD);
                    })
                    .build()
    );

    public RetroConsole(IEventBus modEventBus, ModContainer modContainer) {
        BOOT_LOGGER.info("JVM args: {}", ManagementFactory.getRuntimeMXBean().getInputArguments());

        // Loader facades (packet delivery, current server). Pure wiring, no behaviour change.
        NeoForgePlatform.init();

        // Register the legacy toml specs only while config/retroconsole.json is
        // missing: NeoForgeConfig.install() imports their values once, after
        // which the JSON file is the single source of truth. Directory creation
        // stays lazy — RetroConsolePaths creates each folder on first read; the
        // first caller is usually the first time the player inserts a console
        // block, well after config loading.
        if (NeoForgeConfig.needsMigration()) {
            modContainer.registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
            modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        }

        // Nothing client-typed may appear in this class: FML resolves every
        // declared member here while constructing the mod, so a lambda taking a
        // Screen would make a dedicated server fail with "Attempted to load
        // class net/minecraft/client/gui/screens/Screen for invalid dist".
        if (net.neoforged.fml.loading.FMLEnvironment.getDist()
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            if (NeoForgeConfig.needsMigration()) {
                modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
            }
            NeoForgeConfigScreen.register(modContainer);
        }

        NeoForgeRegistries.register(modEventBus);
        RetroConsoleGameTests.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        // NeoForgeNetwork + NeoForgeSetup + ModKeys register via @SubscribeEvent on the MOD bus
    }
}
