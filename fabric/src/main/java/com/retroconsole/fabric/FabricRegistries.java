package com.retroconsole.fabric;

import com.retroconsole.reg.ModBlockEntities;
import com.retroconsole.reg.ModBlocks;
import com.retroconsole.reg.ModItems;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric registration: the shared objects in {@code common/reg} are fed straight
 * to the vanilla registries, the counterpart of {@code NeoForgeRegistries}.
 *
 * <p>The creative tab is the one piece without a shared instance, because its
 * builder is loader-specific; the entries themselves still come from
 * {@code ModBlocks}/{@code ModItems}.
 */
public final class FabricRegistries {

    private FabricRegistries() {}

    public static void register() {
        block("retro_console", ModBlocks.RETRO_CONSOLE);
        block("screen", ModBlocks.SCREEN);

        item("retro_console", ModBlocks.RETRO_CONSOLE_ITEM);
        item("screen", ModBlocks.SCREEN_ITEM);
        item("gamepad", ModItems.GAMEPAD);

        blockEntity("retro_console", ModBlockEntities.RETRO_CONSOLE_BE);
        blockEntity("screen", ModBlockEntities.SCREEN_BE);

        ResourceKey<CreativeModeTab> tabKey = ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("retroconsole_tab"));
        CreativeModeTab tab = FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.retroconsole"))
                .icon(() -> new ItemStack(ModBlocks.RETRO_CONSOLE))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tabKey, tab);

        ItemGroupEvents.modifyEntriesEvent(tabKey).register(entries -> {
            entries.accept(ModBlocks.RETRO_CONSOLE_ITEM);
            entries.accept(ModBlocks.SCREEN_ITEM);
            entries.accept(ModItems.GAMEPAD);
        });
    }

    private static void block(String path, net.minecraft.world.level.block.Block block) {
        Registry.register(BuiltInRegistries.BLOCK, id(path), block);
    }

    private static void item(String path, net.minecraft.world.item.Item item) {
        Registry.register(BuiltInRegistries.ITEM, id(path), item);
    }

    private static void blockEntity(String path, net.minecraft.world.level.block.entity.BlockEntityType<?> type) {
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(path), type);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(RetroConsoleFabric.MOD_ID, path);
    }
}
