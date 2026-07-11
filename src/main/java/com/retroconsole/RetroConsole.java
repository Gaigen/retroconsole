package com.retroconsole;

import com.retroconsole.config.ModConfig;
import com.retroconsole.reg.ModBlockEntities;
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
                    .icon(() -> new ItemStack(ModBlocks.RETRO_CONSOLE.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.RETRO_CONSOLE_ITEM.get());
                        output.accept(ModBlocks.SCREEN_ITEM.get());
                    })
                    .build()
    );

    public RetroConsole(IEventBus modEventBus, ModContainer modContainer) {
        BOOT_LOGGER.info("JVM args: {}", ManagementFactory.getRuntimeMXBean().getInputArguments());

        // Register the config so ModConfig.* Spec values become resolvable.
        // Directory creation itself is lazy — RetroConsolePaths creates each
        // folder on first read; the first caller is usually the first time
        // the player inserts a console block, well after config loading.
        modContainer.registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

        if (net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            modContainer.registerExtensionPoint(
                    net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                    net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
        }

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        // NetworkHandler + ModKeys register via @SubscribeEvent on MOD bus
    }
}
