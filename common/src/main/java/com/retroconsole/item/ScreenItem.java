package com.retroconsole.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/** Screen block item; the tooltip documents the shell cycle, the dye repaint and the mount. */
public class ScreenItem extends BlockItem {

    public ScreenItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("retroconsole.screen.tooltip.style")
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("retroconsole.screen.tooltip.mount")
                .withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("retroconsole.tone.dye_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
