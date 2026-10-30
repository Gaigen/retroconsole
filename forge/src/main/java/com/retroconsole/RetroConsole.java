package com.retroconsole;

import com.retroconsole.client.ForgeConfigScreen;
import com.retroconsole.config.ModConfig;
import com.retroconsole.platform.ForgeConfig;
import com.retroconsole.platform.ForgePlatform;
import com.retroconsole.platform.ForgeNetwork;
import com.retroconsole.platform.ForgeRegistries;
import com.retroconsole.reg.ModBlocks;
import com.retroconsole.reg.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.registries.DeferredRegister;
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

    public RetroConsole() {
        IEventBus modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        net.minecraftforge.fml.ModLoadingContext modContainer = net.minecraftforge.fml.ModLoadingContext.get();
        BOOT_LOGGER.info("JVM args: {}", ManagementFactory.getRuntimeMXBean().getInputArguments());

        // Loader facades (packet delivery, current server). Pure wiring, no behaviour change.
        ForgePlatform.init();
        ForgeNetwork.registerPackets();

        // Register the legacy toml specs only while config/retroconsole.json is
        // missing: ForgeConfig.install() imports their values once, after
        // which the JSON file is the single source of truth. Directory creation
        // stays lazy — RetroConsolePaths creates each folder on first read; the
        // first caller is usually the first time the player inserts a console
        // block, well after config loading.
        if (ForgeConfig.needsMigration()) {
            modContainer.registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
            modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        }

        // Nothing client-typed may appear in this class: FML resolves every
        // declared member here while constructing the mod, so a lambda taking a
        // Screen would make a dedicated server fail with "Attempted to load
        // class net/minecraft/client/gui/screens/Screen for invalid dist".
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist
                == net.minecraftforge.api.distmarker.Dist.CLIENT) {
            if (ForgeConfig.needsMigration()) {
                modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
            }
            ForgeConfigScreen.register();
        }

        ForgeRegistries.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        // ForgeNetwork + ForgeSetup + ModKeys register via @SubscribeEvent on the MOD bus
    }
}
