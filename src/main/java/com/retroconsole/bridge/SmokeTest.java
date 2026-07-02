package com.retroconsole.bridge;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Smoke test: load core + ROM, run 3 frames, check framebuffer.
 */
public class SmokeTest {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: SmokeTest <core.so> <rom>");
            System.exit(1);
        }

        Path corePath = Paths.get(args[0]).toAbsolutePath();
        Path romPath = Paths.get(args[1]).toAbsolutePath();
        Path systemDir = romPath.getParent().resolve("system");
        Path saveDir = romPath.getParent().resolve("saves");

        System.out.println("Core: " + corePath);
        System.out.println("ROM:  " + romPath);
        System.out.println("System: " + systemDir);

        try {
            LibretroCore core = LibretroCore.load(corePath);
            core.setDirectories(systemDir.toString(), saveDir.toString());
            System.out.println("Core loaded.");

            boolean loaded = core.loadGame(romPath);
            System.out.println("Game loaded: " + loaded);
            System.out.println("Resolution: " + core.getWidth() + "x" + core.getHeight());

            if (loaded) {
                for (int i = 0; i < 3; i++) {
                    core.runFrame();
                    int w = core.getWidth();
                    int h = core.getHeight();
                    int[] buf = new int[w * h];
                    boolean got = core.pollFrame(buf);
                    int nonZero = 0;
                    for (int px : buf) if (px != 0) nonZero++;
                    System.out.printf("Frame %d: %dx%d, gotFrame=%s, nonZeroPixels=%d%n",
                            i, w, h, got, nonZero);
                }
            }

            core.close();
            System.out.println("SUCCESS");
        } catch (Exception e) {
            System.err.println("FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
