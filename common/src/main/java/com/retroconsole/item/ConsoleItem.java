package com.retroconsole.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** Console block item; the tooltip documents the chassis-style cycle and the dye repaint. */
public class ConsoleItem extends BlockItem {

    public ConsoleItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("retroconsole.retro_console.tooltip.style")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("retroconsole.retro_console.tooltip.tone")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("retroconsole.tone.dye_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
