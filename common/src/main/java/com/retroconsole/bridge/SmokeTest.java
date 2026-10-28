package com.retroconsole.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.nio.file.Paths;

/**
 * Smoke test: load a software-rendered core and ROM, then require video frames.
 */
public class SmokeTest {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: SmokeTest <core> <rom-or-directory> [work-directory]");
            System.exit(1);
        }

        Path corePath = Paths.get(args[0]).toAbsolutePath();
        try {
            Path romPath = Paths.get(args[1]).toAbsolutePath();
            if (Files.isDirectory(romPath)) {
                try (var files = Files.list(romPath)) {
                    romPath = files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                                    .matches(".*\\.(sfc|smc|nes|gb|gbc|gba|md|gen)$"))
                            .sorted(Comparator.comparing(Path::toString)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("No cartridge ROM in directory"));
                }
            }
            if (!Files.isRegularFile(corePath) || !Files.isRegularFile(romPath)) {
                throw new IllegalArgumentException("Core and ROM must be existing files");
            }
            Path workDir = args.length >= 3 ? Paths.get(args[2]).toAbsolutePath()
                    : Files.createTempDirectory("retroconsole-native-smoke-");
            Path systemDir = Files.createDirectories(workDir.resolve("system"));
            Path saveDir = Files.createDirectories(workDir.resolve("saves"));
            System.out.println("Java: " + Runtime.version());
            System.out.println("Core: " + corePath);
            System.out.println("ROM:  " + romPath);
            System.out.println("Work: " + workDir);

            int frames = 0;
            boolean visiblePixels = false;
            try (LibretroCore core = LibretroCore.load(corePath, systemDir.toString(), saveDir.toString())) {
                if (!core.loadGame(romPath)) throw new IllegalStateException("Core rejected ROM");
                for (int i = 0; i < 180; i++) {
                    core.runFrame();
                    int w = core.getWidth();
                    int h = core.getHeight();
                    if (w <= 0 || h <= 0) continue;
                    int[] buf = new int[Math.multiplyExact(w, h)];
                    if (!core.pollFrame(buf)) continue;
                    frames++;
                    for (int px : buf) {
                        if ((px & 0x00FFFFFF) != 0) { visiblePixels = true; break; }
                    }
                    if (frames == 1 || i == 179) {
                        System.out.printf("Frame %d: %dx%d, visiblePixels=%s%n", i, w, h, visiblePixels);
                    }
                }
                if (frames == 0) throw new IllegalStateException("No video frames produced");
                if (!visiblePixels) throw new IllegalStateException("Only black video frames produced");
            }
            System.out.println("SUCCESS: " + frames + " video frames; core closed.");
        } catch (Exception e) {
            System.err.println("FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
