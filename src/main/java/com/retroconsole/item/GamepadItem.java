package com.retroconsole.item;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.reg.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Wireless gamepad item. Shift+right-click a retro console to link;
 * right-click in air to play from first-person while watching the in-world TV.
 */
public class GamepadItem extends Item {

    private static final String TAG_CONSOLE_POS = "ConsolePos";

    public GamepadItem(Properties properties) {
        super(properties);
    }

    public static boolean isGamepad(ItemStack stack) {
        return stack.is(ModItems.GAMEPAD.get());
    }

    @Nullable
    public static BlockPos getLinkedConsole(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag tag = custom.copyTag();
        if (!tag.contains(TAG_CONSOLE_POS)) return null;
        return BlockPos.of(tag.getLong(TAG_CONSOLE_POS));
    }

    public static boolean isLinkedTo(ItemStack stack, BlockPos consolePos) {
        BlockPos linked = getLinkedConsole(stack);
        return linked != null && linked.equals(consolePos);
    }

    public static void setLinkedConsole(ItemStack stack, @Nullable BlockPos consolePos) {
        if (consolePos == null) {
            stack.remove(DataComponents.CUSTOM_DATA);
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_CONSOLE_POS, consolePos.asLong());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof RetroConsoleBlockEntity)) {
            return InteractionResult.PASS;
        }

        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        if (!level.isClientSide()) {
            setLinkedConsole(stack, pos.immutable());
            player.displayClientMessage(
                    Component.translatable("retroconsole.gamepad.linked", pos.getX(), pos.getY(), pos.getZ()),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }

        BlockPos linked = getLinkedConsole(stack);
        if (linked == null) {
            if (level.isClientSide()) {
                player.displayClientMessage(Component.translatable("retroconsole.gamepad.not_linked"), true);
            }
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            GamepadScreens.open(linked);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        BlockPos linked = getLinkedConsole(stack);
        if (linked != null) {
            tooltip.add(Component.translatable("retroconsole.gamepad.tooltip.linked",
                    linked.getX(), linked.getY(), linked.getZ()));
        } else {
            tooltip.add(Component.translatable("retroconsole.gamepad.tooltip.unlinked"));
        }
        tooltip.add(Component.translatable("retroconsole.gamepad.tooltip.hint"));
    }
}
