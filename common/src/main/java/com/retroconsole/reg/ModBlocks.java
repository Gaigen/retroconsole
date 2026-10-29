package com.retroconsole.reg;

import com.retroconsole.block.RetroConsoleBlock;
import com.retroconsole.block.ScreenBlock;
import com.retroconsole.item.ConsoleItem;
import com.retroconsole.item.ScreenItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;

/**
 * Block and block-item instances shared by every loader.
 *
 * <p>Only the definitions live here. Each loader hands these exact objects to
 * its own registry (NeoForge in {@code NeoForgeRegistries}, Fabric via
 * {@code Registry.register}), so shared code never has to name a loader API or
 * wait for a deferred holder.
 */
public final class ModBlocks {

    public static final RetroConsoleBlock RETRO_CONSOLE = new RetroConsoleBlock(
            BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("retroconsole", "retro_console")))
                    .mapColor(MapColor.METAL)
                    .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
    );

    public static final ScreenBlock SCREEN = new ScreenBlock(
            BlockBehaviour.Properties.of()
                    .setId(ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("retroconsole", "screen")))
                    .mapColor(MapColor.METAL)
                    .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .isViewBlocking((state, getter, pos) -> false)
    );

    public static final ConsoleItem RETRO_CONSOLE_ITEM = new ConsoleItem(RETRO_CONSOLE, new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("retroconsole", "retro_console")))
            .useBlockDescriptionPrefix());

    public static final ScreenItem SCREEN_ITEM = new ScreenItem(SCREEN, new Item.Properties()
            .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("retroconsole", "screen")))
            .useBlockDescriptionPrefix());

    private ModBlocks() {}
}
