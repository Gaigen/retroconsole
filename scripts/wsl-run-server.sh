#!/usr/bin/env bash
# Linux dedicated server (NeoForge dev run). Connect from Windows client: localhost:25565
set -euo pipefail
cd "${RETROCONSOLE_ROOT:-$HOME/dev/retroconsole}"
./gradlew runServer --no-daemon "$@"
