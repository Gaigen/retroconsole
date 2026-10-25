package com.retroconsole.reg;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.block.ScreenBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Block entity types shared by every loader.
 *
 * <p>The types are built eagerly from the shared block instances; the loader
 * module is what puts them into the game registry. Note that the datafixer
 * type argument of {@code build} is unused by every version we target, hence
 * {@code null}.
 */
public final class ModBlockEntities {

    public static final BlockEntityType<RetroConsoleBlockEntity> RETRO_CONSOLE_BE =
            BlockEntityType.Builder.of(
                    RetroConsoleBlockEntity::new,
                    ModBlocks.RETRO_CONSOLE
            ).build(null);

    public static final BlockEntityType<ScreenBlockEntity> SCREEN_BE =
            BlockEntityType.Builder.of(
                    ScreenBlockEntity::new,
                    ModBlocks.SCREEN
            ).build(null);

    private ModBlockEntities() {}
}
