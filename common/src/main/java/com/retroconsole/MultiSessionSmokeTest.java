package com.retroconsole;

import com.retroconsole.bridge.LibretroCore;

import java.nio.file.Path;

/**
 * 1) Two CONCURRENT sessions of the same core (separate module slots).
 * 2) Sequential restart (slot reuse after close).
 * Run: java -cp ... com.retroconsole.MultiSessionSmokeTest <core.dll> [rom1] [rom2]
 */
public final class MultiSessionSmokeTest {

    public static void main(String[] args) throws Exception {
        Path core = Path.of(args[0]);
        Path rom1 = args.length > 1 ? Path.of(args[1]) : null;
        Path rom2 = args.length > 2 ? Path.of(args[2]) : rom1;
        String system = Path.of("config/retroconsole/system").toAbsolutePath().toString();
        String save = Path.of("config/retroconsole/save").toAbsolutePath().toString();

        System.out.println("=== PHASE 1: two concurrent sessions ===");
        LibretroCore a = LibretroCore.load(core, system, save);
        LibretroCore b = LibretroCore.load(core, system, save);
        run(a, rom1, "A");
        run(b, rom2, "B");
        for (int i = 0; i < 120; i++) { a.runFrame(); b.runFrame(); }
        b.close();
        for (int i = 0; i < 60; i++) a.runFrame(); // A must survive B's close
        a.close();

        System.out.println("=== PHASE 2: restart on reused slot ===");
        LibretroCore c = LibretroCore.load(core, system, save);
        run(c, rom1, "C");
        c.close();

        System.out.println("OK: concurrent sessions + restart survived");
    }

    private static void run(LibretroCore c, Path rom, String tag) {
        if (rom == null) return;
        if (!c.loadGame(rom)) throw new IllegalStateException("loadGame failed: " + tag);
        System.out.println("loadGame " + tag + " -> OK");
    }
}
