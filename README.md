# RetroConsole

NeoForge mod for **Minecraft 1.21.1** that runs [libretro](https://www.libretro.com/) cores inside the game. Place a console block in the world, pick a game from the built-in library UI, and play on an in-world TV screen or in a fullscreen viewer.

**Version:** 0.1.0 (early release — expect rough edges)

## Requirements

| Component | Version |
|-----------|---------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x (tested with 21.1.215) |
| Java | 21 |
| Host OS | **Windows** or **Linux** (64-bit) |

macOS is **not** supported. The mod does **not** ship libretro cores, BIOS files, or ROMs — you must supply those yourself (see below).

## Quick start (players)

1. Install NeoForge 1.21.1 and drop `retroconsole-0.1.0.jar` into the `mods` folder.
2. Start the game. On first launch the mod creates `config/retroconsole/` under your game directory.
3. Download libretro cores (`.dll` on Windows, `.so` on Linux) from the [libretro buildbot](https://buildbot.libretro.com/) and place them in `config/retroconsole/cores/`.
4. Add your legally obtained ROMs under `config/retroconsole/roms/`, preferably in subfolders (`nes/`, `gba/`, `ps2/`, …).
5. In Creative mode, open the **Retro Console** tab and place a **Retro Console** block.
6. Right-click the console → the game library opens. Select a game and press **Launch**.
7. While a game is running, right-click again to open the fullscreen **TV** view.

### Optional: world TV screens

Place **Screen** blocks so that the **Retro Console touches** any block of the screen wall (face-adjacent, 6-neighbour). Linking is physical contact only — there is no radius search.

- Adjacent screens with the same facing/orientation form one rectangular wall (L-shapes split into separate rectangles).
- Look straight ahead to place a wall screen; look sharply down/up to place on the floor/ceiling (like CC:Tweaked monitors).
- **Breaking change:** older worlds that relied on “within 16 blocks” auto-link need the console moved flush against the wall (or the wall rebuilt).

## Quick start (developers)

1. Clone the repo and open it as a Gradle project (IntelliJ IDEA recommended).
2. Use **Java 21**.
3. Run the `runClient` Gradle task, or:

```bash
./gradlew runClient
```

On Windows:

```bat
gradlew.bat runClient
```

Dev paths are under `runs/client/config/retroconsole/` (single-player) or `runs/server/config/retroconsole/` (dedicated server).

## Directory layout

All paths are relative to the **game directory** (client folder in SP, server folder on a dedicated server). Defaults can be changed in `config/retroconsole-common.toml`.

```
config/retroconsole/
├── cores/              # libretro cores (*_libretro.dll / *_libretro.so)
├── roms/               # ROMs and disc images (use subfolders per system)
│   ├── nes/
│   ├── gba/
│   ├── ps2/
│   └── dreamcast/
├── system/             # BIOS and core system files (not shipped with the mod)
│   ├── pcsx2/bios/     # PS2 BIOS (e.g. scph70000.bin) — see BIOS section
│   ├── pcsx2/memcards/ # PS2 memory cards (LRPS2)
│   └── dc/             # Dreamcast BIOS / VMU (Flycast)
├── art/                # Optional menu cover art: art/<folder>.png
├── systems.json        # Optional custom system definitions (local SP only)
└── saves/              # Battery saves, save states, per-player data
    └── players/<uuid>/ # Per-player saves and play-time stats (multiplayer)
```

The mod auto-creates default `roms/` subfolders for built-in systems (`nes`, `snes`, `gb`, `gba`, `genesis`, `sms`, `ps1`, `ps2`, `psp`, `dreamcast`, `segacd`, `saturn`).

### Cores

- File name (without extension) is the **core id** used internally, e.g. `nestopia_libretro.dll` → core id `nestopia_libretro`.
- Download matching builds for your OS from the [libretro buildbot](https://buildbot.libretro.com/).
- A bundled headless OpenGL helper (`.libheadless_gl.dll` / `.libheadless_gl.so`) is extracted into `cores/` automatically for HW-render cores (PS2, Dreamcast, etc.).

### ROMs

- Put files in the matching `roms/<folder>/` subfolder. The folder name becomes a tab in the library UI.
- Disc-based systems accept common formats (`.iso`, `.bin`, `.cue`, `.chd`, `.gdi`, `.cdi`, …) — detection is extension-based.
- **You are responsible for only using ROMs and BIOS files you have the legal right to use.** This mod does not include or endorse piracy.

### BIOS (PS2, Dreamcast, …)

| System | Path | Notes |
|--------|------|-------|
| PlayStation 2 | `system/pcsx2/bios/` | Valid PS2 BIOS dump (4–8 MB). `scph70000.bin` is preferred if present. Required for LRPS2 / PCSX2 cores. |
| Dreamcast | `system/dc/` | Flycast BIOS / VMU files as required by the core. |
| Other | `system/` | Depends on the core (PS1, Saturn, etc.). Consult the core's libretro documentation. |

## Supported systems (built-in catalog)

The library UI recognizes these folders and suggests matching core name patterns. **Compatibility depends on the specific core you install** — the table is a hint, not a guarantee.

| Tab folder | Console | Common extensions | Core name hints |
|------------|---------|-------------------|-----------------|
| `nes` | NES / Famicom | `.nes` | fceumm, nestopia, mesen |
| `snes` | Super Nintendo | `.sfc`, `.smc` | snes9x, bsnes |
| `gb` | Game Boy / Color | `.gb`, `.gbc` | gambatte, sameboy |
| `gba` | Game Boy Advance | `.gba` | mgba, vba |
| `genesis` | Genesis / Mega Drive | `.gen`, `.md` | genesis_plus_gx, picodrive |
| `sms` | Master System | `.sms`, `.gg` | smsplus, genesis_plus_gx |
| `ps1` | PlayStation | `.bin`, `.cue`, `.pbp`, … | swanstation, pcsx_rearmed, beetle_psx |
| `ps2` | PlayStation 2 | `.iso`, `.bin`, `.chd`, … | pcsx2, lrps2 |
| `psp` | PSP | `.iso`, `.cso` | ppsspp |
| `dreamcast` | Dreamcast | `.cdi`, `.gdi`, `.chd` | flycast |
| `segacd` | Sega CD | disc images | genesis_plus_gx, picodrive |
| `saturn` | Saturn | disc images | mednafen_saturn, beetle_saturn, kronos |

Custom tabs can be added via `systems.json` (on the machine that serves the library — your client in single-player, the server in multiplayer).

### Tested cores (0.1.0)

Smoke-tested during development on **Windows 11** (NeoForge 21.1.215). Linux has matching native helpers; treat HW cores there as “should work”, not as a full matrix.

| Core file (libretro) | System | Notes |
|----------------------|--------|--------|
| `nestopia_libretro` | NES | Software render |
| `snes9x_libretro` | SNES | Software render |
| `mgba_libretro` | GBA | Software render |
| `genesis_plus_gx_libretro` | Genesis / Mega Drive | Software render |
| `pcsx_rearmed_libretro` | PS1 | Software render |
| `pcsx2_libretro` (LRPS2) | PS2 | HW OpenGL via `headless_gl`; needs BIOS under `system/pcsx2/bios/`; multi-console OK |
| `ppsspp_libretro` | PSP | HW OpenGL via `headless_gl`; multi-console OK |
| `flycast_libretro` | Dreamcast | HW OpenGL via `headless_gl`; needs DC BIOS under `system/dc/`; multi-console OK |

Other cores from the catalog table above may work, but were **not** part of the 0.1.0 smoke set.

## Multiplayer

On a **dedicated server**, the game library is built from the **server's** `config/retroconsole/` (ROMs, cores, `systems.json`, `art/`). Clients do not scan their local `roms/` folder in multiplayer.

| Data | Where it lives in multiplayer |
|------|-------------------------------|
| ROM / core list | Server disk |
| Emulator execution | Server |
| Play time & launch count | Server (`saves/players/<uuid>/`) |
| Favorites, volume, core overrides | Client-local |
| Save states & battery saves | Server (`saves/players/<uuid>/`) |

Only the player who launched a game can send input, save states, and power off that console (within 8 blocks). Nearby players receive the world TV picture (video ~48 blocks from the console; opening the fullscreen TV view also subscribes you).

**Important:** ROM and core files must exist on the server at the same relative paths the client shows in the library.

## Controls

Default keyboard bindings (rebindable in **Options → Controls → Retro Console**):

| Key | Action |
|-----|--------|
| Arrow keys | D-Pad |
| Z | A |
| X | B |
| C | X |
| V | Y |
| Q | L1 |
| W | R1 |
| E | L2 |
| R | R2 |
| N | L3 (left stick click) |
| M | R3 (right stick click) |
| Enter | Start |
| Right Shift | Select |
| I / K / J / L | Left analog stick |
| T / G / F / H | Right analog stick |

In the **TV** fullscreen view:

| Key | Action |
|-----|--------|
| F1 | Toggle control help overlay |
| F5 | Quick save (slot 0) |
| F6 | Quick load (slot 0) |
| Esc | Exit TV view (autosave runs on exit) |

In the **library** UI: arrow keys navigate, Enter launches, right-click toggles favorite, click the **?** button for help.

## Building a release JAR

```bash
./gradlew build
```

Output: `build/libs/retroconsole-0.1.0.jar`

To rebuild the bundled headless GL native library (developers only):

```bash
./gradlew buildHeadlessGL copyHeadlessGLToResources
```

Linux builds produce `.so`; Windows builds produce `.dll`. Ship both in `src/main/resources/natives/` before cross-platform releases.

## Architecture (overview)

```
LibretroCore (JNA) → LibretroRuntime → ThreadedEmulatorRuntime
        ↓                                      ↓
ServerConsoles ← FrameSenderThread (~60 Hz) ← RetroFramePacket / RetroAudioPayload
        ↓
ClientConsoles → DynamicTexture → TvScreen / ScreenBlockEntityRenderer
```

Emulation runs on the **server thread pool** (one retro thread per active console). Video and audio stream to nearby clients.

## License

### RetroConsole (this repository)

RetroConsole mod source code, Java resources, and the bundled `headless_gl` native helper are licensed under the **[GNU General Public License v3.0](LICENSE)** (GPL-3.0-or-later).

If you distribute this mod or a modified version, you must provide corresponding source code under the same license. See the [LICENSE](LICENSE) file for the full text.

### libretro API

This mod implements the [libretro API](https://docs.libretro.com/development/libretro-overview/), which is a separate, MIT-licensed specification. RetroConsole is **not** RetroArch and is **not** affiliated with the libretro project.

### Third-party cores and content

**Libretro cores are not included.** Each core is its own project with its own license (GPL, LGPL, non-commercial, etc.). Check the license of every core you download:

- [libretro licenses overview](https://docs.libretro.com/development/licenses/)
- [libretro buildbot](https://buildbot.libretro.com/)

**BIOS files and ROMs are not included.** You must obtain them legally. The authors of RetroConsole are not responsible for how you acquire or use copyrighted game software.

### Other dependencies

| Component | License |
|-----------|---------|
| [JNA](https://github.com/java-native-access/jna) | LGPL-2.1 / Apache-2.0 (dual) |
| NeoForge / Minecraft | See their respective terms |

When distributing a build of this mod, include the [LICENSE](LICENSE) file and respect the licenses of any cores and assets you bundle alongside it.
