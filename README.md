# RetroConsole

[![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-62B47A?style=flat-square)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-26.1.x-orange?style=flat-square)](https://neoforged.net/)
[![Fabric](https://img.shields.io/badge/Fabric-0.20.1%2B-dbd0b4?style=flat-square)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Windows%20%7C%20Linux-lightgrey?style=flat-square)](#requirements)

NeoForge and Fabric mod for **Minecraft 26.1.2** (the All the Mods 11 target) that runs [libretro](https://www.libretro.com/) cores inside the game. Place a console block in the world, pick a game from the built-in library UI, and play on an in-world TV screen or in a fullscreen viewer.

**Version:** 0.3.2 (early release — expect rough edges)

## Download

Pre-built JARs: **[GitHub Releases](https://github.com/Gaigen/retroconsole/releases)**

| Loader | File |
|--------|------|
| NeoForge | `retroconsole-0.3.2+26.1.2-neoforge.jar` |
| Fabric | `retroconsole-0.3.2+26.1.2-fabric.jar` (needs Fabric API) |

Requirements: Minecraft **26.1.2**, NeoForge **26.1.2.124** or Fabric Loader **0.20.1+** with **Fabric API 0.145.1+26.1**, Java **25**. The mod does **not** include libretro cores, BIOS, or ROMs. Both loaders read the same `config/retroconsole.json`, so settings carry over between them.

## What's new in 0.3.2

- **New client UI (opt-in)** — the library, settings, in-game panel and bezel are redrawn: three library layouts (consoles / list / manual pick) with search, favourites and a per-game card popup; all six settings sections with the same fields, ranges and `retroconsole.json`; a bezel page that lists the real images, previews, enables, scales and reloads without tearing down the streamed session. Toggle it in **Library → UI → New interface**; the classic UI stays the default on a clean profile and is one click away. The choice is local (`config/retroconsole/client-ui.properties`) and never goes to the server.
- **Panel instead of always-on buttons** — **F4** opens/closes the panel, **F1** opens Controls, **Esc** closes the viewer while input is not captured. With keyboard/mouse capture Esc stays the game's key and **Delete** releases capture and opens the panel. The panel does not pause the core: the picture refits beside it and keeps running.
- **Controls built from the real bindings** — a two-column table read from the live `KeyMapping`s (both sticks, extra DS actions), directions in ↑ ↓ ← → order, ellipsis plus hover for long rebinds. Help has localized paragraph sections, two columns when wide, scrolling with the wheel / PageUp / PageDown when narrow, and a pinned header and footer.
- **Bundled UI font** — Liberation Sans (SIL OFL) with Cyrillic, shipped as its own font (`retroconsole:ui`) rasterized per GUI scale 1–8, used only by the new UI; the Controls table uses a slightly smaller variant so rows stay inside their cells. The vanilla font and the classic UI are untouched.
- **Carried fixes** — window resize no longer recreates the viewer, its subscription or the panel state; one P2 button that sends the next connect/disconnect request; **Start over** launches without the auto-state and does **not** delete SRAM or in-game saves; single manual slot 0 (F5/F6) plus the separate auto-state; save and load results are never faked in the UI, and a missing thumbnail reads "no frame yet" instead of showing someone else's.

## What's new in 0.3.1

- **Minecraft 26.1.2** — this branch targets 26.1.2 on both loaders (NeoForge 26.1.2.124 / FancyModLoader 11, Fabric Loader 0.20.1 with Loom 1.19.3) and needs **Java 25**. The shared `common/` sources are the ones 0.3.0 shipped, so behaviour carries over; the 1.21.1 release stays at 0.3.0 for now.
- **Console chassis styles** — five silhouettes (`classic`, `compact`, `hifi`, `legacy`, `fullblock`), each rendered in three finishes (`cream`, `graphite`, `lavender`) and three visual states (`off`, `standby`, `running`). Style and finish are cosmetic and can be cycled on a placed console.
- **Screen shell designs and mounts** — five frames (`retro`, `studio`, `wall`, `edge`, `solid`), dye-based finish, and a **front/back mount**; all of it applies to the whole linked wall (the mount is deliberately not sneak-cyclable).
- **Frameless full-block screen** — the `solid` design drops the bezel so the picture fills the entire block face.
- **Gamepad item** — three shells (`soft`, `brick`, `grip`) in the same three finishes, sharing one held/ground/item-frame pose.
- **Screen geometry cache hardening** — the per-tile shape cache is keyed on the full grid slot instead of packing it into 8-bit fields, is synchronized and bounded (4096 entries), and returns empty geometry for stale grid data instead of throwing during chunk/cluster updates.
- **mkxp-z size handling** — returning to the base canvas size clears a stale probe and an earlier rejected size, so a later genuine resize to that same size is no longer ignored.
- **`[control]` diagnostics** — power-off, launch (with `loadAuto`) and save-state requests are logged at INFO under the `RetroConsole-Control` logger with player, position and console id; useful when a report says "the button does nothing".
- **Five new GameTests** (`retroconsole:parity`) cover legacy UUID/NBT round-trip, block-entity survival across cosmetic changes, screen grid NBT, power-off without a running core, and vanilla placement of style/tone.

## What's new in 0.3.0

- **Fabric** — RetroConsole now ships as a Fabric mod as well as a NeoForge one, with the same features and the same `config/retroconsole.json`; settings, saves and play stats carry over if you switch loaders.
- **RPG Maker XP / VX / VX Ace** through the `mkxp-z` core — one library entry per game folder, named from `Title=` in `Game.ini`, a correctly sized picture even for games that scale their window, and a chat message that names the missing RTP instead of leaving you at a black screen.
- **Legible text on screens** — the in-world downscale averages pixels instead of dropping them, and the TV view switches to linear sampling at non-integer scales, so 1-pixel glyph stems survive the reduction.
- **Launching and stopping a big game no longer freezes the world** — cores boot off the tick thread, and automatic save-state resume is skipped when a core's state is huge (mkxp-z serializes its whole Ruby heap, ~100 MB).
- **`[debug] verboseLogs`** — new ⚙ → **Debug** toggle for heartbeats and GL capture diagnostics; off by default, so a normal log stays readable.

## Features

- In-world **Retro Console** block with a game library UI (favorites, search, per-system tabs)
- **Screen** multiblock walls — console must **touch** the wall (CC:Tweaked-style floor/ceiling placement)
- Fullscreen **TV** viewer with volume slider and quick save/load
- Volume 0 mutes **server-side**: no PCM is streamed to that client
- Server-side emulation with video/audio streaming to nearby players
- Per-player saves, play-time stats, PS2 memory-card sync
- In-game config UI (paths, streaming distances, session limits, video presets)
- Optional **new client UI** with bundled font, side panel and bezel settings (classic UI still available)
- HW-render cores (PS2, PSP, Dreamcast, Doom 3, RPG Maker) via bundled headless OpenGL helper
- **Doom 3** through the boom3 core — retail or the free demo
- **RPG Maker XP / VX / VX Ace** through the mkxp-z core — one library entry per game folder, RTP-aware
- Nintendo DS / 3DS with absolute touch pointer (DeSmuME, melonDS, Citra)

## Screenshots

*Screenshots coming soon.*

## Requirements

| Component | Version |
|-----------|---------|
| Minecraft | 26.1.2 |
| NeoForge | 26.1.2.124 (FancyModLoader 11.0.15) |
| Fabric | Loader 0.20.1+ and Fabric API 0.145.1+26.1 |
| Java | 25 |
| Host OS | **Windows** or **Linux** (64-bit) |

macOS is **not** supported.

## Quick start (players)

1. Install NeoForge 26.1.2, or Fabric Loader 26.1.2 plus **Fabric API**, and drop the matching jar into the `mods` folder.
2. Start the game. On first launch the mod creates `config/retroconsole/` under your game directory.
3. Download libretro cores (`.dll` on Windows, `.so` on Linux) from the [libretro buildbot](https://buildbot.libretro.com/) and place them in `config/retroconsole/cores/`.
4. Add your legally obtained ROMs under `config/retroconsole/roms/`, preferably in subfolders (`nes/`, `gba/`, `ps2/`, …).
5. Craft a **Retro Console** and **Screen** blocks (or take them from the **Retro Console** creative tab):

```
Retro Console          Screen ×2
I I I                  G G G
I R I                  G R G
I G I                  I I I

I = iron ingot, R = redstone, G = glass pane
```

6. Place the console, then place screens so the console **touches** the wall. Right-click the console → the game library opens. Select a game and press **Launch**.
7. While a game is running, right-click again to open the fullscreen **TV** view.

### World TV screens

Place **Screen** blocks so that the **Retro Console touches** any block of the screen wall (face-adjacent, 6-neighbour). Linking is physical contact only — there is no radius search.

- Adjacent screens with the same facing/orientation form one rectangular wall (L-shapes split into separate rectangles).
- Look straight ahead to place a wall screen; look sharply down/up to place on the floor/ceiling (like CC:Tweaked monitors).
- **Breaking change (0.1.1):** older worlds that relied on “within 16 blocks” auto-link need the console moved flush against the wall (or the wall rebuilt).

## Quick start (developers)

1. Clone the repo and open it as a Gradle project (IntelliJ IDEA recommended):

   ```bash
   git clone https://github.com/Gaigen/retroconsole.git
   ```

2. Use **Java 25** — the Gradle toolchain is pinned to it (`java.toolchain.languageVersion = 25`) and 26.x will not run on 21.
3. Run the NeoForge client:

   ```bash
   ./gradlew :neoforge:runClient
   ```

   On Windows:

   ```bat
   gradlew.bat :neoforge:runClient
   ```

   `neoForgeClient` / `fabricClient` work from the root too. A bare `./gradlew runClient` is ambiguous (Gradle matches the task in every project that has one), so always name the loader.

   Fabric client (the `fabric` module is opt-in):

   ```bash
   ./gradlew :fabric:runClient -Pfabric
   ```

   The repo is split into `common/` (loader-free code, assets, natives), `neoforge/` and `fabric/` (glue). Each loader module compiles the shared sources into one self-contained jar, and the Fabric dev runs reuse the NeoForge run directory so both loaders see the same cores and ROMs.

Dev paths are under `neoforge/runs/client/config/retroconsole/` (single-player) or `neoforge/runs/server/config/retroconsole/` (dedicated server).

## Directory layout

All paths are relative to the **game directory** (client folder in SP, server folder on a dedicated server). Defaults can be changed in `config/retroconsole.json`.

```
config/retroconsole/
├── cores/              # libretro cores (*_libretro.dll / *_libretro.so)
├── roms/               # ROMs and disc images (use subfolders per system)
│   ├── nes/
│   ├── gba/
│   ├── ps2/
│   ├── dreamcast/
│   └── rpgmaker/<game>/  # RPG Maker games are folders — see the mkxp-z section
├── system/             # BIOS and core system files (not shipped with the mod)
│   ├── pcsx2/bios/     # PS2 BIOS (e.g. scph70000.bin) — see BIOS section
│   ├── pcsx2/memcards/ # PS2 memory cards (LRPS2)
│   ├── mkxp-z/RTP/     # RPG Maker run-time packages (Standard, RPGVX, RPGVXAce)
│   └── dc/             # Dreamcast BIOS / VMU (Flycast)
├── art/                # Optional menu cover art: art/<folder>.png
├── systems.json        # Optional custom system definitions (local SP only)
└── saves/              # Battery saves, save states, per-player data
    └── players/<uuid>/ # Per-player saves and play-time stats (multiplayer)
```

The mod auto-creates default `roms/` subfolders for built-in systems (`nes`, `snes`, `gb`, `gba`, `genesis`, `sms`, `ps1`, `ps2`, `psp`, `dreamcast`, `segacd`, `saturn`).

### Cores

- File name (without extension) is the **core id** used internally, e.g. `nestopia_libretro.dll` → core id `nestopia_libretro`.
- Download matching builds for your OS from the [libretro buildbot](https://buildbot.libretro.com/). The `mkxp-z` core is not hosted there — see [its section](#rpg-maker-xp--vx--vx-ace-mkxp-z).
- A bundled headless OpenGL helper (`.libheadless_gl.dll` / `.libheadless_gl.so`) is extracted into `cores/` automatically for HW-render cores (PS2, Dreamcast, etc.).

### ROMs

- Put files in the matching `roms/<folder>/` subfolder. The folder name becomes a tab in the library UI.
- Disc-based systems accept common formats (`.iso`, `.bin`, `.cue`, `.chd`, `.gdi`, `.cdi`, …) — detection is extension-based.
- **You are responsible for only using ROMs and BIOS files you have the legal right to use.** This mod does not include or endorse piracy.

### Doom 3 (boom3)

Doom 3 is not a single ROM file: the core (`boom3_libretro`) loads a game data folder.

```
config/retroconsole/roms/doom3/
├── base/   # pak000.pk4 … pak008.pk4 (retail) or demo00.pk4 (free demo)
└── d3xp/   # optional: Resurrection of Evil
```

Every folder under `roms/doom3/` becomes an entry in the **Doom 3** tab. The core renders through the bundled headless OpenGL helper, so a GPU with OpenGL 3.3+ is required. On Windows, `tools\setup-boom3.ps1` downloads the core and creates the folders. If the stream stutters, lower `doom3Resolution` in `[video]`.

### RPG Maker XP / VX / VX Ace (mkxp-z)

A RPG Maker game is a folder, not a single ROM file, so the **RPG Maker** tab shows one entry per game folder (named after `Title=` when there is one):

```
config/retroconsole/roms/rpgmaker/
└── <game>/                 # folder holding Game.ini, mkxp.json or a project file
    ├── Game.ini
    ├── Data/
    └── Graphics/
```

The `mkxp-z` core mounts the folder that contains `Game.ini` / `mkxp.json` / `*.rxproj` and renders through the bundled headless OpenGL helper. Keep a game's assets in its own folder — pointing the library at the parent folder makes the core mount the parent, so the game starts with nothing to draw.

The core is **not** on the libretro buildbot: build `mkxp-z_libretro` from the [mkxp-z source](https://github.com/mkxp-z/mkxp-z) and drop the `.dll` / `.so` into `cores/`. Upstream documentation: [libretro → mkxp-z](https://docs.libretro.com/library/mkxp-z/).

Games that declare an **RTP** in `Game.ini` (`RTP1=Standard`) need that Runtime Package unpacked into `system/mkxp-z/RTP/<name>/`. The standard packages come from [rpgmakerweb.com/run-time-package](https://www.rpgmakerweb.com/run-time-package) — extract the installer (`innoextract` works on any OS), keep the folder that contains `Audio/` and `Graphics/`, and name it `Standard` (XP), `RPGVX` (VX) or `RPGVXAce` (VX Ace). Without it the core keeps running but draws almost nothing, which looks like a black screen; its log says `Failed to locate run time package … required by the game`, and the game's chat message names the missing package and the folder it belongs in.

mkxp-z serializes its entire Ruby heap into a save state — tens of megabytes for a large game. Automatic resume is therefore skipped when a state would exceed 32 MB, so stopping a game no longer freezes the world while 100 MB is written to disk. Quick save/load slots (F5 / F6) and the game's own save file are unaffected.

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
| `doom3` | Doom 3 | `.pk4` (game data folder) | boom3 |
| `rpgmaker` | RPG Maker XP / VX / VX Ace | game folder (`.rxproj`, `.rvproj2`, …) | mkxp-z |

Custom tabs can be added via `systems.json` (on the machine that serves the library — your client in single-player, the server in multiplayer).

### Tested cores (0.3.1)

The matrix below was established on **Windows 11** for 0.3.0 (NeoForge 21.1.215). The 26.1.2 release smoke re-ran `genesis_plus_gx` (Genesis) and `mkxp-z` (RPG Maker) end to end; the remaining rows are carried over unchanged and are **not** yet re-verified on 26.1.2. The Fabric build runs the same shared code and differs only in loader glue. Linux has matching native helpers; treat HW cores there as “should work”, not as a full matrix.

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
| `boom3_libretro` | Doom 3 | HW OpenGL via `headless_gl`; game data under `roms/doom3/`; multi-console OK |
| `mkxp-z_libretro` | RPG Maker XP / VX / VX Ace | HW OpenGL via `headless_gl`; one entry per game folder; RTPs under `system/mkxp-z/RTP/` |

Other cores from the catalog table above may work, but were **not** part of the 0.3.0 smoke set.

## Configuration

Settings open **in-game**:

1. Open the console library → **⚙** button (top right), or
2. Main menu → **Mods** → RetroConsole → **Config** (NeoForge). Fabric has no built-in mod list, so use the **⚙** button there.

One file, created on first launch:

| File | Contents |
|------|----------|
| `config/retroconsole.json` | Paths to cores/ROMs/system/saves, streaming, limits, video, input, debug |

On a **dedicated server**, edit the server’s copy (or ask the host) — clients cannot change server streaming/video from the UI.

> Upgrading from 0.2.6 or older? The old `retroconsole-common.toml` / `retroconsole-server.toml` are read once on the first launch and folded into `retroconsole.json`; the toml files are then left untouched and ignored.

### `[streaming]` (defaults)

| Key | Default | Description |
|-----|---------|-------------|
| `videoDistance` | 48 | Max distance (blocks) to receive video frames |
| `audioDistance` | 32 | Max distance for emulator audio |
| `viewSubscribeDistance` | 64 | Fullscreen TV subscribe radius |
| `controlDistance` | 8 | Max distance to send controller input |
| `notifyDistance` | 256 | Radius for “console stopped” notifications |
| `worldMaxWidth` | 480 | Cap in-world frame width (fullscreen TV is not affected) |

### `[limits]` (defaults)

| Key | Default | Description |
|-----|---------|-------------|
| `maxCoreSlots` | 0 | Max simultaneous copies of the same core DLL (0 = unlimited). On Windows each active console needs its own slot. |
| `maxPcsx2Sessions` | 8 | Max parallel PS2 sessions (separate system dirs + memcards) |
| `maxScreenCluster` | 1024 | Max blocks in one linked screen wall |
| `batteryAutosaveSeconds` | 30 | Interval for battery-save flush to disk |

When a limit is hit, the owner gets a chat message (e.g. core slot cap, PS2 session cap, missing BIOS) instead of a generic load failure.

### `[video]`

| Key | Default | Description |
|-----|---------|-------------|
| `preset` | `balanced` | `performance`, `balanced`, or `quality` |
| `flycastInternalResolution` | *(preset)* | Override Flycast internal res |
| `flycastTexUpscale` | *(preset)* | Override texture upscale |
| `flycastAnisotropic` | *(preset)* | Override anisotropic filtering |
| `ppssppInternalResolution` | *(preset)* | Override PPSSPP internal res |
| `ppssppTextureScaling` | *(preset)* | Override PPSSPP texture scaling |
| `pcsx2UpscaleMultiplier` | *(preset)* | Override PCSX2 upscale (e.g. `"2"`, `"3"`) |
| `pcsx2Anisotropic` | *(preset)* | Override PCSX2 anisotropic filtering |
| `citraResolution` | *(preset)* | Override Citra resolution (e.g. `"2x"`) |
| `doom3Resolution` | *(preset)* | Override Doom 3 internal res — the main cost knob (`640x360`, `960x540`, `1920x1080`) |
| `doom3InvertY` | `false` | Invert Doom 3 mouse / right-stick Y axis |

Video overrides apply the next time you **launch** a game. Leave override strings empty to keep the preset value.

### `[debug]`

| Key | Default | Description |
|-----|---------|-------------|
| `verboseLogs` | `false` | Periodic call-stats heartbeats and GL capture diagnostics in the log (⚙ → **Debug**). Worth turning on when reporting a rendering bug; leave it off otherwise. |

## Multiplayer

On a **dedicated server**, the game library is built from the **server's** `config/retroconsole/` (ROMs, cores, `systems.json`, `art/`). Clients do not scan their local `roms/` folder in multiplayer.

| Data | Where it lives in multiplayer |
|------|-------------------------------|
| ROM / core list | Server disk |
| Emulator execution | Server |
| Play time & launch count | Server (`saves/players/<uuid>/`) |
| Favorites, volume, core overrides | Client-local |
| Save states & battery saves | Server (`saves/players/<uuid>/`) |

Distances for control / video / audio come from the server's `[streaming]` section (defaults: control 8, video 48, audio 32).

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
./gradlew :neoforge:build                         # NeoForge jar only
./gradlew :neoforge:build :fabric:build -Pfabric  # both loaders
./gradlew build verifyDistribution -Pfabric       # both jars + metadata/asset/native checks
```

Outputs:

- `neoforge/build/libs/retroconsole-0.3.2+26.1.2-neoforge.jar`
- `fabric/build/libs/retroconsole-0.3.2+26.1.2-fabric.jar`

`verifyDistribution` unzips both jars, asserts the loader metadata, the shared classes, the blockstates, the recipes and the natives are present, then walks every model an item or blockstate points at and requires it — together with each `parent` and every texture along the chain — to be inside the jar. Run it locally before tagging: the release workflow only calls `build`, so this is the gate that stops a renamed or missing model from shipping. The release workflow builds the Linux `.so` and the Windows `.dll` helper first, then both jars with the same tasks.

To rebuild the bundled headless GL native library (developers only):

```bash
./gradlew :neoforge:buildHeadlessGL :neoforge:copyHeadlessGLToResources
```

Linux builds produce `.so`; Windows builds produce `.dll`. Ship both in `common/src/main/resources/natives/` before cross-platform releases.

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
