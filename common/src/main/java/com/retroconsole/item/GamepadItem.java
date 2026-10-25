package com.retroconsole.item;

import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.reg.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
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
import java.util.UUID;

/**
 * Wireless gamepad item. Shift+right-click a retro console to link;
 * right-click in air to play from first-person while watching the in-world TV.
 */
public class GamepadItem extends Item {

    private static final String TAG_CONSOLE_ID = "ConsoleId";
    /** Legacy hint for resolving console after a move. */
    private static final String TAG_CONSOLE_POS = "ConsolePos";

    public GamepadItem(Properties properties) {
        super(properties);
    }

    public static boolean isGamepad(ItemStack stack) {
        return stack.is(ModItems.GAMEPAD.get());
    }

    @Nullable
    public static UUID getLinkedConsoleId(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag tag = custom.copyTag();
        if (tag.hasUUID(TAG_CONSOLE_ID)) {
            return tag.getUUID(TAG_CONSOLE_ID);
        }
        return null;
    }

    @Nullable
    public static BlockPos getLinkedConsolePos(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag tag = custom.copyTag();
        if (tag.contains(TAG_CONSOLE_POS)) {
            return BlockPos.of(tag.getLong(TAG_CONSOLE_POS));
        }
        return null;
    }

    public static boolean isLinkedTo(ItemStack stack, UUID consoleId) {
        UUID linked = getLinkedConsoleId(stack);
        return linked != null && linked.equals(consoleId);
    }

    public static boolean isLinkedTo(ItemStack stack, BlockPos consolePos, UUID consoleId) {
        if (consoleId != null && isLinkedTo(stack, consoleId)) return true;
        BlockPos linkedPos = getLinkedConsolePos(stack);
        return linkedPos != null && linkedPos.equals(consolePos);
    }

    public static void setLinkedConsole(ItemStack stack, UUID consoleId, BlockPos consolePos) {
        if (consoleId == null) {
            stack.remove(DataComponents.CUSTOM_DATA);
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_CONSOLE_ID, consoleId);
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
        if (!(be instanceof RetroConsoleBlockEntity console)) {
            return InteractionResult.PASS;
        }

        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        ItemStack stack = context.getItemInHand();
        if (!level.isClientSide()) {
            setLinkedConsole(stack, console.getConsoleId(), pos.immutable());
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

        BlockPos linkedPos = resolveConsolePos(level, player, stack);
        if (linkedPos == null) {
            if (level.isClientSide()) {
                player.displayClientMessage(Component.translatable("retroconsole.gamepad.not_linked"), true);
            }
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide()) {
            GamepadScreens.open(linkedPos);
        }
        return InteractionResultHolder.success(stack);
    }

    @Nullable
    private static BlockPos resolveConsolePos(Level level, Player player, ItemStack stack) {
        UUID consoleId = getLinkedConsoleId(stack);
        BlockPos hint = getLinkedConsolePos(stack);
        if (hint != null && level.isLoaded(hint)) {
            BlockEntity be = level.getBlockEntity(hint);
            if (be instanceof RetroConsoleBlockEntity console
                    && (consoleId == null || console.getConsoleId().equals(consoleId))) {
                return hint;
            }
        }
        if (consoleId == null) {
            return hint;
        }
        int r = 64;
        BlockPos center = player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(
                center.offset(-r, -r, -r), center.offset(r, r, r))) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof RetroConsoleBlockEntity console
                    && console.getConsoleId().equals(consoleId)) {
                return p.immutable();
            }
        }
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        BlockPos linked = getLinkedConsolePos(stack);
        if (linked != null) {
            tooltip.add(Component.translatable("retroconsole.gamepad.tooltip.linked",
                    linked.getX(), linked.getY(), linked.getZ()));
        } else {
            tooltip.add(Component.translatable("retroconsole.gamepad.tooltip.unlinked"));
        }
        tooltip.add(Component.translatable("retroconsole.gamepad.tooltip.hint"));

        if (Screen.hasAltDown()) {
            tooltip.add(Component.literal("For d_aranda, who saw the potential before anyone else.")
                    .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.ITALIC));
        }
    }
}
