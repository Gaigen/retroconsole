# RetroConsole 0.3.1 — Minecraft 1.20.1

This branch targets **Minecraft 1.20.1**, **Java 17**, **Forge 47.4.0** or **Fabric Loader 0.16.14 + Fabric API 0.92.2+1.20.1**. Install only the JAR for your loader, on both server and client. Forge and Fabric editions are separate; they do not provide cross-loader multiplayer.

Revision **0.3.1-backport.2** fixes the 1.20.1 slider geometry and narrow-GUI layouts. See `UI-AUDIT-R2.md` for the follow-up review.

## Build

Use JDK 17, then:

```sh
./gradlew build
```

Outputs:
- `forge/build/libs/retroconsole-0.3.1-backport.2+1.20.1-forge.jar`
- `fabric/build/libs/retroconsole-0.3.1-backport.2+1.20.1-fabric.jar`

The wrapper uses Gradle 8.8. First build requires network access to Maven and Minecraft downloads. Both loader modules compile the same `common` sources into self-contained mod JARs. `neoforge/` is retained as upstream reference but is **not** included by `settings.gradle`; it is not a supported 1.20.1 target. Historical README/WSL instructions are retained for reference; use commands here for this branch. Original CI definitions are retained in `upstream-reference/`.

## Development and tests

```sh
./gradlew :forge:runClient
./gradlew :fabric:runClient
./gradlew :forge:runServer
./gradlew :fabric:runServer
./gradlew :forge:runGameTestServer
./gradlew :fabric:test :fabric:runGamepadSmokeTest
```

Forge development uses `run/forge`; Fabric uses `run/client` and `run/server`. Start scripts select Forge. To rebuild the optional native headless OpenGL helper, use `./gradlew :forge:buildHeadlessGL` with the required platform compiler and EGL/OpenGL development headers installed. Prebuilt Linux and Windows helpers from the supplied 1.21.1 sources are preserved in the mod resources; ordinary Java builds do not rebuild them.

## Ported areas

- Forge registration, configuration migration, server/client lifecycle, renderer and key registration, HUD hiding and config-screen entry.
- Fabric 1.20.1 channel-ID/FriendlyByteBuf networking and 1.20.1 HUD mixins.
- All 18 payload definitions, using internal bounded wire codecs instead of Minecraft 1.21 StreamCodec/ByteBufCodecs.
- Gamepad appearance and console UUID/position linkage in ItemStack NBT. Changing appearance preserves linkage; unlinking preserves appearance and unrelated tags.
- Console/screen block entity persistence and update packets; 1.20.1 block interactions; vertex emission and GUI/mouse-scroll signatures.
- Resource pack version 15, plural data directories, legacy recipe result and advancement item-predicate formats. Loot copy_state preserves screen appearance in BlockStateTag.
- Shared libretro runtime, audio, video streaming, ROM catalog, save states, controller/keyboard/mouse/touch support, bezels and chassis assets retained from the supplied 1.21.1 implementation.

## Installation and scope

Back up worlds and saves first. Do not open a Minecraft 1.21/26.x world directly in Minecraft 1.20.1. The port does not convert newer worlds or item-component data into old-world data.

Libretro cores, BIOS, ROMs and copyrighted games are **not included**. Supply appropriate platform builds of the cores and your own legally obtained content, following the upstream usage documentation. Windows/Linux native and GPU-core behavior still needs testing on the target machine; a successful Java build is not certification of every emulator core.

Optional Linux native ABI test (a synthetic fixture, not a real emulator core):

```sh
gcc -shared -fPIC -O2 tests/native/test_core.c -o tests/native/test_core.so
./gradlew :fabric:test -PnativeSmokeCore="$PWD/tests/native/test_core.so"
```

See `VERIFICATION.md` for the exact executed checks and remaining validation gaps.
