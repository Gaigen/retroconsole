package com.retroconsole.client;

import com.retroconsole.network.RetroCoreSelectPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Screen for selecting a libretro core and ROM for a console block.
 */
public class CoreSelectScreen extends Screen {
    private final BlockPos consolePos;

    private List<String> cores = new ArrayList<>();
    private List<String> roms = new ArrayList<>();
    private int selectedCore = -1;
    private int selectedRom = -1;
    private int scrollCore = 0;
    private int scrollRom = 0;
    private static final int VISIBLE = 8;

    public CoreSelectScreen(BlockPos consolePos) {
        super(Component.literal("Select Core & ROM"));
        this.consolePos = consolePos;
    }

    @Override
    protected void init() {
        super.init();
        // Scan cores — store display name (without extension) + original filename
        cores = scanCores("config/retroconsole/cores");
        roms = scanDir("config/retroconsole/roms", ".nes", ".gb", ".gba", ".sfc", ".smc", ".gen", ".md", ".sms", ".gg", ".zip", ".bin", ".cue", ".iso", ".chd", ".cdi", ".gdi");

        addRenderableWidget(Button.builder(Component.literal("Start"), b -> startEmulator())
                .pos(width / 2 - 50, height - 30)
                .size(100, 20)
                .build());
    }

    // Override to remove blur/dark overlay
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, width, height, 0xC0101010);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);

        g.drawCenteredString(font, "Select Core", width / 4, 10, 0xFFFFFF);
        g.drawCenteredString(font, "Select ROM", width * 3 / 4, 10, 0xFFFFFF);

        int listW = width / 2 - 20;
        int listY = 30;
        int itemH = 14;

        // Core list
        for (int i = 0; i < VISIBLE && scrollCore + i < cores.size(); i++) {
            int idx = scrollCore + i;
            int y = listY + i * itemH;
            String name = cores.get(idx);
            int color = idx == selectedCore ? 0x55FF55 : 0xAAAAAA;
            boolean hover = mx >= 10 && mx <= 10 + listW && my >= y && my < y + itemH;
            if (hover) color = 0xFFFFFF;
            g.fill(10, y, 10 + listW, y + itemH, idx == selectedCore ? 0x303060 : 0x202020);
            g.drawString(font, name, 12, y + 2, color);
        }

        // ROM list
        int romX = width / 2 + 10;
        for (int i = 0; i < VISIBLE && scrollRom + i < roms.size(); i++) {
            int idx = scrollRom + i;
            int y = listY + i * itemH;
            String name = roms.get(idx);
            int color = idx == selectedRom ? 0x55FF55 : 0xAAAAAA;
            boolean hover = mx >= romX && mx <= romX + listW && my >= y && my < y + itemH;
            if (hover) color = 0xFFFFFF;
            g.fill(romX, y, romX + listW, y + itemH, idx == selectedRom ? 0x303060 : 0x202020);
            g.drawString(font, name, romX + 2, y + 2, color);
        }

        if (selectedCore >= 0 && selectedRom >= 0) {
            g.drawCenteredString(font, "Ready! Click Start.", width / 2, height - 45, 0x55FF55);
        }

        super.render(g, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int listW = width / 2 - 20;
        int listY = 30;
        int itemH = 14;

        for (int i = 0; i < VISIBLE && scrollCore + i < cores.size(); i++) {
            int idx = scrollCore + i;
            int y = listY + i * itemH;
            if (mx >= 10 && mx <= 10 + listW && my >= y && my < y + itemH) {
                selectedCore = idx;
                return true;
            }
        }

        int romX = width / 2 + 10;
        for (int i = 0; i < VISIBLE && scrollRom + i < roms.size(); i++) {
            int idx = scrollRom + i;
            int y = listY + i * itemH;
            if (mx >= romX && mx <= romX + listW && my >= y && my < y + itemH) {
                selectedRom = idx;
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double hy) {
        if (mx < width / 2) {
            scrollCore = Math.max(0, Math.min(scrollCore - (int) Math.signum(hy), Math.max(0, cores.size() - VISIBLE)));
        } else {
            scrollRom = Math.max(0, Math.min(scrollRom - (int) Math.signum(hy), Math.max(0, roms.size() - VISIBLE)));
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void startEmulator() {
        if (selectedCore < 0 || selectedRom < 0) return;
        String coreName = cores.get(selectedCore);
        String romId = roms.get(selectedRom);
        PacketDistributor.sendToServer(new RetroCoreSelectPacket(consolePos, coreName, romId));
        Minecraft.getInstance().setScreen(new TvScreen(consolePos));
    }

    /**
     * Scan cores directory, return names WITHOUT extension (matching CoreManager.findCore).
     */
    private List<String> scanCores(String dirPath) {
        List<String> result = new ArrayList<>();
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase();
                if (lower.endsWith(".so") || lower.endsWith(".dll") || lower.endsWith(".dylib")) {
                    // Strip extension to match CoreManager.findCore()
                    result.add(name.substring(0, name.lastIndexOf('.')));
                }
            }
        } catch (IOException ignored) {}
        result.sort(String::compareToIgnoreCase);
        return result;
    }

    /**
     * Scan ROMs directory, return full filenames (used as romId).
     */
    private List<String> scanDir(String dirPath, String... extensions) {
        List<String> result = new ArrayList<>();
        Path dir = Paths.get(dirPath);
        if (!Files.exists(dir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString().toLowerCase();
                for (String ext : extensions) {
                    if (name.endsWith(ext)) {
                        result.add(p.getFileName().toString());
                        break;
                    }
                }
            }
        } catch (IOException ignored) {}
        result.sort(String::compareToIgnoreCase);
        return result;
    }
}
