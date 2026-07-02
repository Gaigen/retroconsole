package com.retroconsole.reg;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "retroconsole");

    public static final Supplier<BlockEntityType<RetroConsoleBlockEntity>> RETRO_CONSOLE_BE =
            BLOCK_ENTITIES.register(
                    "retro_console",
                    () -> BlockEntityType.Builder.of(
                            RetroConsoleBlockEntity::new,
                            ModBlocks.RETRO_CONSOLE.get()
                    ).build(null)
            );

    public static final Supplier<BlockEntityType<ScreenBlockEntity>> SCREEN_BE =
            BLOCK_ENTITIES.register(
                    "screen",
                    () -> BlockEntityType.Builder.of(
                            ScreenBlockEntity::new,
                            ModBlocks.SCREEN.get()
                    ).build(null)
            );

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }

    private ModBlockEntities() {}
}
