package com.retroconsole.block;

import com.retroconsole.server.ServerConsoles;
import net.minecraft.util.StringRepresentable;

/**
 * Visual power state of a console: the LED colour and whether a cartridge is
 * seated. It mirrors the emulator runtime, it does not drive it.
 */
public enum ConsoleState implements StringRepresentable {
    OFF("off"),
    STANDBY("standby"),
    RUNNING("running");

    private final String name;

    ConsoleState(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    static ConsoleState of(RetroConsoleBlockEntity console) {
        if (console.getRomId().isEmpty()) {
            return OFF;
        }
        var consoleId = console.getConsoleId();
        if (consoleId != null && (ServerConsoles.hasEmulator(consoleId) || ServerConsoles.isBooting(consoleId))) {
            return RUNNING;
        }
        return STANDBY;
    }
}
