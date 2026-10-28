package com.retroconsole.gametest;

import com.retroconsole.RetroConsole;
import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.reg.ModBlockEntities;
import com.retroconsole.reg.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.function.Consumer;

/**
 * Dedicated-server smoke tests, launched with {@code ./gradlew runGameTestServer}.
 * <p>
 * The template resolves to {@code retroconsole:empty}, i.e.
 * {@code data/retroconsole/structure/empty.nbt}, and is an empty 3x3x3 area
 * (its structure block sits one block below the test area).
 */
public final class RetroConsoleGameTests {

    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, RetroConsole.MOD_ID);
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CONSOLE_REGISTERS = FUNCTIONS.register(
            "console_registers_on_dedicated_server", () -> RetroConsoleGameTests::consoleRegistersOnDedicatedServer);

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(RetroConsoleGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(Identifier.fromNamespaceAndPath(RetroConsole.MOD_ID, "default"));
        event.registerTest(CONSOLE_REGISTERS.getId(), new FunctionGameTestInstance(
                CONSOLE_REGISTERS.getKey(), new TestData<>(environment,
                Identifier.fromNamespaceAndPath(RetroConsole.MOD_ID, "empty"), 200, 0, true)));
    }

    public static void consoleRegistersOnDedicatedServer(GameTestHelper helper) {
        // The instances are shared with common/, so what matters here is that
        // NeoForge actually put them into the game registries.
        if (BuiltInRegistries.BLOCK.getKey(ModBlocks.RETRO_CONSOLE) == null) {
            helper.fail("retro_console block is not registered");
            return;
        }
        if (BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(ModBlockEntities.RETRO_CONSOLE_BE) == null) {
            helper.fail("retro_console block entity is not registered");
            return;
        }

        // Relative coordinates start at the structure block; the area is above it.
        BlockPos pos = new BlockPos(1, 2, 1);
        helper.setBlock(pos, ModBlocks.RETRO_CONSOLE);
        helper.assertBlockPresent(ModBlocks.RETRO_CONSOLE, pos);
        if (helper.getBlockEntity(pos, RetroConsoleBlockEntity.class) == null) {
            helper.fail("retro_console block entity missing at " + pos);
            return;
        }
        helper.succeed();
    }

    private RetroConsoleGameTests() {}
}
