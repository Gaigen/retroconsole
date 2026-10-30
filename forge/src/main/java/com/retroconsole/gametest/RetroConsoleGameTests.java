package com.retroconsole.gametest;

import com.retroconsole.RetroConsole;
import com.retroconsole.reg.ModBlockEntities;
import com.retroconsole.reg.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Dedicated-server smoke tests, launched with {@code ./gradlew runGameTestServer}.
 * <p>
 * The template resolves to {@code retroconsole:empty}, i.e.
 * {@code data/retroconsole/structure/empty.nbt}, and is an empty 3x3x3 area
 * (its structure block sits one block below the test area).
 */
@GameTestHolder(RetroConsole.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RetroConsoleGameTests {

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void consoleRegistersOnDedicatedServer(GameTestHelper helper) {
        // The instances are shared with common/, so what matters here is that
        // Forge actually put them into the game registries.
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
        if (helper.getBlockEntity(pos) == null) {
            helper.fail("retro_console block entity missing at " + pos);
            return;
        }
        helper.succeed();
    }

    private RetroConsoleGameTests() {}
}
