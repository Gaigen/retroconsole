#!/usr/bin/env bash
# Linux client (needs WSLg / DISPLAY). Usually run client on Windows instead.
set -euo pipefail
cd "${RETROCONSOLE_ROOT:-$HOME/dev/retroconsole}"
./gradlew runClient --no-daemon "$@"
