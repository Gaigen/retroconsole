#!/usr/bin/env bash
# One-time sync Windows checkout -> native WSL copy (faster Gradle/IDE).
set -euo pipefail
SRC="${1:-/mnt/c/dev/retroconsole}"
DST="${2:-$HOME/dev/retroconsole}"

mkdir -p "$(dirname "$DST")"
rsync -a --delete \
  --exclude='.gradle' \
  --exclude='build' \
  --exclude='runs' \
  --exclude='.idea' \
  --exclude='out' \
  "$SRC/" "$DST/"
chmod +x "$DST/gradlew"

mkdir -p "$DST/runs/server/config/retroconsole"/{cores,roms,system,saves,art}
mkdir -p "$DST/runs/client/config/retroconsole"/{cores,roms,system,saves,art}
test -f "$DST/runs/server/eula.txt" || printf 'eula=true\n' > "$DST/runs/server/eula.txt"

echo "Synced to $DST"
echo "Open in IDE: \\\\wsl$\\Ubuntu-24.04\\home\\$(whoami)\\dev\\retroconsole"
