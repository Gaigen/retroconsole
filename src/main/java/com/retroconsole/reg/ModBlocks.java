package com.retroconsole.reg;

import com.retroconsole.block.RetroConsoleBlock;
import com.retroconsole.block.ScreenBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("retroconsole");
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("retroconsole");

    public static final DeferredBlock<RetroConsoleBlock> RETRO_CONSOLE = BLOCKS.register(
            "retro_console",
            () -> new RetroConsoleBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
            )
    );

    public static final DeferredBlock<ScreenBlock> SCREEN = BLOCKS.register(
            "screen",
            () -> new ScreenBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .isViewBlocking((state, getter, pos) -> false)
            )
    );

    public static final DeferredItem<BlockItem> RETRO_CONSOLE_ITEM = ITEMS.register(
            "retro_console",
            () -> new BlockItem(RETRO_CONSOLE.get(), new Item.Properties())
    );

    public static final DeferredItem<BlockItem> SCREEN_ITEM = ITEMS.register(
            "screen",
            () -> new BlockItem(SCREEN.get(), new Item.Properties())
    );

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    private ModBlocks() {}
}
