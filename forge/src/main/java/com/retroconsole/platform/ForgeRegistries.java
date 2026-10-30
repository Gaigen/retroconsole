package com.retroconsole.platform;

import com.retroconsole.RetroConsole;
import com.retroconsole.reg.ModBlockEntities;
import com.retroconsole.reg.ModBlocks;
import com.retroconsole.reg.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;

/**
 * Forge registration for the blocks, items and block entity types defined
 * in {@code common/}.
 *
 * <p>The instances live on the shared side ({@link ModBlocks}, {@link ModItems},
 * {@link ModBlockEntities}) so the Fabric module can hand the same objects to
 * {@code Registry.register}. Nothing is deferred there — the DeferredRegister
 * wrappers below only carry the registry names.
 */
public final class ForgeRegistries {

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, RetroConsole.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, RetroConsole.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RetroConsole.MOD_ID);

    /**
     * Called from the mod constructor, which runs before the mod event bus
     * processes any registration event. Touching {@link ModBlocks},
     * {@link ModItems} and {@link ModBlockEntities} here also makes sure the
     * shared instances exist before the registries ask for them.
     */
    public static void register(IEventBus modBus) {
        BLOCKS.register("retro_console", () -> ModBlocks.RETRO_CONSOLE);
        BLOCKS.register("screen", () -> ModBlocks.SCREEN);

        ITEMS.register("retro_console", () -> ModBlocks.RETRO_CONSOLE_ITEM);
        ITEMS.register("screen", () -> ModBlocks.SCREEN_ITEM);
        ITEMS.register("gamepad", () -> ModItems.GAMEPAD);

        BLOCK_ENTITIES.register("retro_console", () -> ModBlockEntities.RETRO_CONSOLE_BE);
        BLOCK_ENTITIES.register("screen", () -> ModBlockEntities.SCREEN_BE);

        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    private ForgeRegistries() {}
}
