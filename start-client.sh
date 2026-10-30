#!/bin/bash
# Запуск Minecraft-клиента с модом RetroConsole (game dir: run/forge)
set -e
cd "$(dirname "$0")"
exec ./gradlew :forge:runClient "$@"
