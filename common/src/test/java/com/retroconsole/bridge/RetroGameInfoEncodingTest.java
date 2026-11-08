package com.retroconsole.bridge;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RetroGameInfoEncodingTest {
    @Test
    void romPathUsesUtf8EvenWhenJnaUsesWindowsCodePage() {
        String previous = System.getProperty("jna.encoding");
        System.setProperty("jna.encoding", "windows-1252");
        try {
            for (String path : new String[] {
                    "C:\\profiles\\《我的世界2》極簡版(恐懼週) 1.0.0\\roms\\game.3ds",
                    "C:\\игры\\テスト (USA) 🎮.3ds",
                    "C:\\roms\\Game (USA).3ds"
            }) {
                var info = new LibretroBridge.RetroGameInfo();
                info.path = path;
                info.meta = "";
                info.write();
                Pointer nativePath = info.getPointer().getPointer(0);
                byte[] expected = (path + "\0").getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(expected, nativePath.getByteArray(0, expected.length));
            }
        } finally {
            if (previous == null) System.clearProperty("jna.encoding");
            else System.setProperty("jna.encoding", previous);
        }
    }
}
