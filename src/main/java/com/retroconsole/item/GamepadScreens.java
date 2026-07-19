package com.retroconsole.item;

import net.minecraft.core.BlockPos;

import java.util.function.Consumer;

/** Client registers the screen opener; common code calls {@link #open}. */
public final class GamepadScreens {

    private static Consumer<BlockPos> opener = pos -> {};

    private GamepadScreens() {}

    public static void setOpener(Consumer<BlockPos> opener) {
        GamepadScreens.opener = opener != null ? opener : pos -> {};
    }

    public static void open(BlockPos consolePos) {
        opener.accept(consolePos);
    }
}
