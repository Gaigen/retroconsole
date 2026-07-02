# RetroConsole — NeoForge 1.21.1 Mod

Libretro-based retro console emulator in Minecraft.

## Quick Start (IntelliJ IDEA)

1. Open `/home/user/retroconsole/` as Gradle project in IntelliJ
2. Wait for Gradle sync to complete
3. Run `runClient` Gradle task
4. In-game: place a **Retro Console** block (Creative tab)
5. Use commands to set core + ROM on the block:

```
/data block <x> <y> <z> set CoreName "nestopia_libretro"
/data block <x> <y> <z> set RomId "homebrew_demo.nes"
```

6. Right-click the block → TvScreen opens
7. Controls: Arrow keys = D-pad, Z = A, X = B, Enter = Start, RShift = Select

## Directory Structure

```
run/config/retroconsole/
├── cores/           ← Place .so/.dll libretro cores here
│   ├── nestopia_libretro.so    (NES)
│   └── mgba_libretro.so        (Game Boy)
├── roms/            ← Place ROM files here
│   └── homebrew_demo.nes
├── system/          ← BIOS files go here
└── saves/           ← SRAM saves (auto-created)
```

## Available Cores (pre-installed)

| Core | Console | File |
|------|---------|------|
| Nestopia | NES/Famicom | `nestopia_libretro.so` |
| mGBA | Game Boy/GBA | `mgba_libretro.so` |

Download more from: https://buildbot.libretro.com/nightly/linux/x86_64/latest/

## Button Mapping

| Key | Libretro Button |
|-----|----------------|
| ↑↓←→ | D-Pad |
| Z | A (face) |
| X | B (face) |
| C | X (face) |
| V | Y (face) |
| Q | L (shoulder) |
| E | R (shoulder) |
| 1 | L2 |
| 2 | R2 |
| Enter | Start |
| RShift | Select |
| IJKL | Left analog |
| Numpad 4568 | Right analog |

## Building

```bash
./gradlew jar
# Output: build/libs/retroconsole-0.1.0.jar
```

## Architecture

```
LibretroCore (JNA) → LibretroRuntime (FrameSource) → ThreadedEmulatorRuntime (60fps daemon)
    ↓                                                      ↓
ServerConsoles ← pollFrame() ← ServerTickHandler ← RetroFramePacket (zlib)
    ↓                                                      ↓
ClientConsoles.updateFrame() → DynamicTexture → TvScreen / ScreenBlockEntityRenderer
```

## Adding New Cores

1. Download `.so` (Linux) or `.dll` (Windows) from libretro buildbot
2. Place in `config/retroconsole/cores/`
3. Set `CoreName` on the console block to match filename (without extension)

## Multi-Block Screens

Place multiple ScreenBlock blocks adjacent to each other, all linked to the same
RetroConsoleBlock. The game frame will span across all connected screen blocks.
