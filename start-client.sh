#!/bin/bash
# Запуск Minecraft-клиента с модом RetroConsole (game dir: runs/client)
set -e
cd "$(dirname "$0")"
exec ./gradlew runClient "$@"
