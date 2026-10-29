package com.retroconsole.client;

import com.retroconsole.platform.RetroPaths;
import com.retroconsole.library.RomLibrary;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import java.nio.file.Path;
import java.util.Properties;

/** Client-only appearance preference. Never sent to the server or stored in retroconsole.json. */
public final class ClientUi {
    private ClientUi() {}
    private static Path file() { return RetroPaths.get().gameDir().resolve("config/retroconsole/client-ui.properties"); }

    public static boolean modern() {
        Properties p = new Properties();
        RomLibrary.loadProps(p, file());
        return "modern".equals(p.getProperty("library", "classic"));
    }

    public static void setModern(boolean modern) {
        Properties p = new Properties();
        RomLibrary.loadProps(p, file());
        p.setProperty("library", modern ? "modern" : "classic");
        RomLibrary.saveProps(p, file(), "RetroConsole client appearance");
    }

    public static Screen library(BlockPos pos) {
        return modern() ? new ModernCoreSelectScreen(pos) : new CoreSelectScreen(pos);
    }
}
