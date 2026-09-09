#!/usr/bin/env bash
# First NeoForge build in native WSL (shows plain progress; run once before IDE runServer).
set -euo pipefail
cd "${RETROCONSOLE_ROOT:-$HOME/dev/retroconsole}"
echo "Project: $(pwd)"
echo "This can take 10-30 min on first run (neoFormTransformSource is silent)."
./gradlew --stop 2>/dev/null || true
./gradlew build --console=plain --no-daemon "$@"
