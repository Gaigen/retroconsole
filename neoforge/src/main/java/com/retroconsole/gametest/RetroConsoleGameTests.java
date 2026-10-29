package com.retroconsole.gametest;

import com.retroconsole.RetroConsole;
import com.retroconsole.block.ConsoleShapes;
import com.retroconsole.block.ConsoleState;
import com.retroconsole.block.ConsoleStyle;
import com.retroconsole.block.RetroConsoleBlock;
import com.retroconsole.block.RetroConsoleBlockEntity;
import com.retroconsole.reg.ModBlockEntities;
import com.retroconsole.reg.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.shapes.Shapes;
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
    private static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CONSOLE_SHAPE = FUNCTIONS.register(
            "console_shape_matches_model", () -> RetroConsoleGameTests::consoleShapeMatchesModel);

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(RetroConsoleGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        var environment = event.registerEnvironment(Identifier.fromNamespaceAndPath(RetroConsole.MOD_ID, "default"));
        for (var function : java.util.List.of(CONSOLE_REGISTERS, CONSOLE_SHAPE)) {
            event.registerTest(function.getId(), new FunctionGameTestInstance(
                    function.getKey(), new TestData<>(environment,
                    Identifier.fromNamespaceAndPath(RetroConsole.MOD_ID, "empty"), 200, 0, true)));
        }
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

    public static void consoleShapeMatchesModel(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        if (RetroConsoleBlock.STYLE.getPossibleValues().size() != ConsoleStyle.values().length
                || RetroConsoleBlock.CONSOLE_STATE.getPossibleValues().size() != ConsoleState.values().length
                || RetroConsoleBlock.FACING.getPossibleValues().size() != 4) {
            helper.fail("Console blockstate does not expose every style, state and facing");
            return;
        }

        var base = ModBlocks.RETRO_CONSOLE.defaultBlockState();
        for (ConsoleStyle style : ConsoleStyle.values()) {
            boolean fullCube = style == ConsoleStyle.FULLBLOCK;
            boolean visibleCartridge = switch (style) {
                case CLASSIC, COMPACT, HIFI -> true;
                case LEGACY, FULLBLOCK -> false;
            };
            if (visibleCartridge
                    && height(ConsoleShapes.boxes(style, ConsoleState.OFF))
                    >= height(ConsoleShapes.boxes(style, ConsoleState.RUNNING))) {
                helper.fail("Cartridge is solid on the " + style + " console while it is not inserted");
                return;
            }
            for (ConsoleState visual : ConsoleState.values()) {
                double top = height(ConsoleShapes.boxes(style, visual));
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    var state = base.setValue(RetroConsoleBlock.STYLE, style)
                            .setValue(RetroConsoleBlock.CONSOLE_STATE, visual)
                            .setValue(RetroConsoleBlock.FACING, facing);
                    var shape = state.getShape(helper.getLevel(), pos);
                    var bounds = shape.bounds();
                    if (shape.isEmpty() || Math.abs(bounds.maxY * 16 - top) > 1e-6) {
                        helper.fail("Console shape does not match " + style + "/" + visual + " for " + facing);
                        return;
                    }
                    if (!Shapes.equal(shape, state.getCollisionShape(helper.getLevel(), pos))) {
                        helper.fail("Console collision and selection shapes differ for "
                                + style + "/" + visual + "/" + facing);
                        return;
                    }
                    if (fullCube != Shapes.equal(shape, Shapes.block())) {
                        helper.fail("Console outline is not a full cube exactly when the style is: " + style);
                        return;
                    }
                    if (!state.propagatesSkylightDown()) {
                        helper.fail("Console blocks skylight for " + style);
                        return;
                    }
                }
            }
        }
        helper.succeed();
    }

    private static double height(double[][] boxes) {
        double top = 0;
        for (double[] box : boxes) {
            top = Math.max(top, box[4]);
        }
        return top;
    }

    private RetroConsoleGameTests() {}
}
