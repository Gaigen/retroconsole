#!/usr/bin/env bash
# Linux dedicated server (Forge dev run). Connect from Windows client: localhost:25565
set -euo pipefail
cd "${RETROCONSOLE_ROOT:-$HOME/dev/retroconsole}"
./gradlew :forge:runServer --no-daemon "$@"
