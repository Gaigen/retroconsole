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
  --exclude='run' \
  --exclude='.idea' \
  --exclude='out' \
  "$SRC/" "$DST/"
chmod +x "$DST/gradlew"

mkdir -p "$DST/run/forge/config/retroconsole"/{cores,roms,system,saves,art}
echo "For a dedicated server, review the Minecraft EULA and set eula=true in run/forge/eula.txt."

echo "Synced to $DST"
echo "Open in IDE: \\\\wsl$\\Ubuntu-24.04\\home\\$(whoami)\\dev\\retroconsole"
