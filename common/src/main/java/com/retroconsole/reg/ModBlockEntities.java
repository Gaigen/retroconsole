package com.retroconsole.reg;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;

/**
 * Block entity types shared by every loader.
 *
 * <p>The types are built eagerly from the shared block instances; the loader
 * module is what puts them into the game registry.
 */
public final class ModBlockEntities {

    public static final BlockEntityType<RetroConsoleBlockEntity> RETRO_CONSOLE_BE =
            new BlockEntityType<>(
                    RetroConsoleBlockEntity::new,
                    Set.of(ModBlocks.RETRO_CONSOLE)
            );

    public static final BlockEntityType<ScreenBlockEntity> SCREEN_BE =
            new BlockEntityType<>(
                    ScreenBlockEntity::new,
                    Set.of(ModBlocks.SCREEN)
            );

    private ModBlockEntities() {}
}
