package com.retroconsole.gametest;

import com.retroconsole.RetroConsole;
import com.retroconsole.block.*;
import com.retroconsole.reg.ModBlocks;
import com.retroconsole.server.ServerConsoles;
import com.retroconsole.style.RetroTone;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.UUID;
import java.util.function.Consumer;

/** Real Minecraft checks of the API/cosmetic boundaries. No native core is loaded. */
public final class PortParityGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, RetroConsole.MOD_ID);
    private static final java.util.List<net.neoforged.neoforge.registries.DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>>> TESTS = java.util.List.of(
            FUNCTIONS.register("parity_console_nbt", () -> PortParityGameTests::consoleNbt),
            FUNCTIONS.register("parity_visual_keeps_game_data", () -> PortParityGameTests::visualKeepsGameData),
            FUNCTIONS.register("parity_screen_nbt", () -> PortParityGameTests::screenNbt),
            FUNCTIONS.register("parity_power_off_keeps_identity", () -> PortParityGameTests::powerOffKeepsIdentity),
            FUNCTIONS.register("parity_vanilla_placement_keeps_finish", () -> PortParityGameTests::placementKeepsFinish));
    private PortParityGameTests() {}
    public static void register(IEventBus bus) {
        FUNCTIONS.register(bus);
        bus.addListener(PortParityGameTests::registerTests);
    }
    private static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(Identifier.fromNamespaceAndPath(RetroConsole.MOD_ID, "parity"));
        for (var function : TESTS) event.registerTest(function.getId(), new FunctionGameTestInstance(
                function.getKey(), new TestData<>(environment,
                Identifier.fromNamespaceAndPath(RetroConsole.MOD_ID, "empty"), 200, 0, true)));
    }
    private static CompoundTag consoleTag(UUID id, UUID owner) {
        CompoundTag tag = new CompoundTag();
        // Same legacy int-array representation written by the 1.21.1 API.
        tag.putIntArray("ConsoleId", UUIDUtil.uuidToIntArray(id));
        tag.putIntArray("OwnerId", UUIDUtil.uuidToIntArray(owner));
        tag.putString("RomId", "nes/parity.nes");
        tag.putString("CoreName", "parity-core");
        return tag;
    }
    private static void load(GameTestHelper helper, net.minecraft.world.level.block.entity.BlockEntity be, CompoundTag tag) {
        be.loadCustomOnly(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
    }
    private static void check(GameTestHelper helper, boolean ok, String message) {
        if (!ok) throw helper.assertionException(net.minecraft.network.chat.Component.literal(message));
    }
    private static void checkConsole(GameTestHelper h, RetroConsoleBlockEntity be, UUID id, UUID owner) {
        check(h, id.equals(be.getConsoleId()) && owner.equals(be.getOwnerId())
                && "nes/parity.nes".equals(be.getRomId()) && "parity-core".equals(be.getCoreName()),
                "Console UUID, owner, ROM or core changed");
    }
    public static void consoleNbt(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, ModBlocks.RETRO_CONSOLE);
        var be = helper.getBlockEntity(pos, RetroConsoleBlockEntity.class);
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        load(helper, be, consoleTag(id, owner));
        checkConsole(helper, be, id, owner);
        var copy = new RetroConsoleBlockEntity(be.getBlockPos(), be.getBlockState());
        load(helper, copy, be.saveCustomOnly(helper.getLevel().registryAccess()));
        checkConsole(helper, copy, id, owner);
        var clientCopy = new RetroConsoleBlockEntity(be.getBlockPos(), be.getBlockState());
        load(helper, clientCopy, be.getUpdateTag(helper.getLevel().registryAccess()));
        checkConsole(helper, clientCopy, id, owner);
        helper.succeed();
    }
    public static void visualKeepsGameData(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 2, 1), pos = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.RETRO_CONSOLE);
        var original = helper.getBlockEntity(relative, RetroConsoleBlockEntity.class);
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        load(helper, original, consoleTag(id, owner));
        for (var style : ConsoleStyle.values()) for (var visual : ConsoleState.values()) for (var tone : RetroTone.values()) {
            var state = original.getBlockState().setValue(RetroConsoleBlock.STYLE, style)
                    .setValue(RetroConsoleBlock.CONSOLE_STATE, visual).setValue(RetroConsoleBlock.TONE, tone);
            helper.getLevel().setBlock(pos, state, Block.UPDATE_ALL);
            check(helper, helper.getLevel().getBlockEntity(pos) == original && !original.isRemoved(),
                    "Cosmetic blockstate replaced or removed the console BE");
            checkConsole(helper, original, id, owner);
        }
        check(helper, !ServerConsoles.hasEmulator(id) && !ServerConsoles.isBooting(id),
                "Cosmetic update started an emulator");
        helper.succeed();
    }
    public static void screenNbt(GameTestHelper helper) {
        var be = new ScreenBlockEntity(BlockPos.ZERO, ModBlocks.SCREEN.defaultBlockState());
        UUID id = UUID.randomUUID();CompoundTag tag = new CompoundTag();
        tag.putIntArray("ConsoleId", UUIDUtil.uuidToIntArray(id));
        tag.putInt("GridX", 2);tag.putInt("GridY", 1);tag.putInt("GridW", 3);tag.putInt("GridH", 2);
        load(helper, be, tag);
        var copy = new ScreenBlockEntity(BlockPos.ZERO, ModBlocks.SCREEN.defaultBlockState());
        load(helper, copy, be.getUpdateTag(helper.getLevel().registryAccess()));
        check(helper, id.equals(copy.getConsoleId()) && copy.getXIndex()==2 && copy.getYIndex()==1
                && copy.getGridWidth()==3 && copy.getGridHeight()==2, "Screen grid/link changed in NBT sync roundtrip");
        helper.succeed();
    }
    public static void powerOffKeepsIdentity(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);helper.setBlock(pos, ModBlocks.RETRO_CONSOLE);
        var be = helper.getBlockEntity(pos, RetroConsoleBlockEntity.class);
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();load(helper, be, consoleTag(id, owner));
        be.powerOff();
        check(helper, be.getRomId().isEmpty() && id.equals(be.getConsoleId()) && owner.equals(be.getOwnerId())
                && "parity-core".equals(be.getCoreName()), "PowerOff changed identity/owner/core or retained ROM");
        var copy = new RetroConsoleBlockEntity(be.getBlockPos(), be.getBlockState());
        load(helper, copy, be.saveCustomOnly(helper.getLevel().registryAccess()));
        check(helper, copy.getRomId().isEmpty() && id.equals(copy.getConsoleId()), "Off did not persist");
        helper.succeed();
    }
    public static void placementKeepsFinish(GameTestHelper helper) {
        BlockPos support = new BlockPos(1,1,1);helper.setBlock(support, Blocks.STONE);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack stack = new ItemStack(ModBlocks.RETRO_CONSOLE);
        RetroTone tone = RetroTone.values()[1];
        stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY
                .with(RetroConsoleBlock.STYLE, ConsoleStyle.HIFI).with(RetroConsoleBlock.TONE, tone));
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos absolute = helper.absolutePos(support);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0,0.5,0),Direction.UP,absolute,false);
        var result = ((BlockItem)stack.getItem()).place(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,hit));
        var placed = helper.getLevel().getBlockState(absolute.above());
        check(helper, result.consumesAction() && placed.is(ModBlocks.RETRO_CONSOLE)
                && placed.getValue(RetroConsoleBlock.STYLE)==ConsoleStyle.HIFI
                && placed.getValue(RetroConsoleBlock.TONE)==tone, "Vanilla BlockItem placement lost saved style/tone");
        helper.succeed();
    }
}
