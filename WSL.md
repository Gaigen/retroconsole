# RetroConsole в WSL

Нативная копия проекта в WSL собирается и запускается **быстрее**, чем с `/mnt/c/...`.

## Путь в WSL

```text
~/dev/retroconsole
```

Windows Explorer / Cursor:

```text
\\wsl$\Ubuntu-24.04\home\<user>\dev\retroconsole
```

## Первичная синхронизация с Windows

Из WSL:

```bash
bash /mnt/c/dev/retroconsole/scripts/wsl-setup.sh
```

Или вручную — скопировать репо в `~/dev/retroconsole` (без `.gradle/`, `build/`, `runs/`).

## IDE

- **Cursor / VS Code:** `Remote - WSL` → Open Folder → `~/dev/retroconsole`
- **IntelliJ:** WSL remote или открыть `\\wsl$\Ubuntu-24.04\home\user\dev\retroconsole`

## Cores / ROMs (сервер)

Положи файлы в **серверную** папку (общая для dev-run):

```text
~/dev/retroconsole/runs/server/config/retroconsole/cores/   # *_libretro.so
~/dev/retroconsole/runs/server/config/retroconsole/roms/    # игры по системам
```

При первом HW-ядре mod сам распакует `.libheadless_gl.so` в `cores/`.

## Запуск

**Сервер (WSL):**

```bash
cd ~/dev/retroconsole
./gradlew runServer
```

**Клиент (Windows)** — проще с Windows-копии:

```powershell
cd C:\dev\retroconsole
.\gradlew.bat runClient
```

Подключение: **Multiplayer → Direct Connect → `localhost`**

`runs/server/server.properties`: `online-mode=false`, порт `25565`.

## Gradle «вечно висит» на neoFormTransformSource

Это **не зависание**, а первый прогон NeoForge: задача `neoFormTransformSource` может молчать **10–30 минут** (CPU 100%, в логе прогресса нет).

**Частые причины:**

| Проблема | Решение |
|---|---|
| IDE открыт на `/mnt/c/dev/retroconsole` | Открыть **`~/dev/retroconsole`** (Remote WSL) |
| Параллельно крутится build на `/mnt/c` | Убить: `pkill -f "/mnt/c/dev/retroconsole.*gradlew"` |
| `runData` | Не нужен для теста — используй **`runServer`** / **`runClient`** |
| Daemon выключен | В WSL один раз: `./gradlew --stop`, потом `./gradlew build --console=plain` |

**Первый раз — только из терминала WSL** (видно, что живой):

```bash
cd ~/dev/retroconsole
./gradlew build --console=plain --no-daemon
```

Дождись `BUILD SUCCESSFUL`. После этого `runServer` / `runClient` из IDE стартуют за секунды.

**Не открывай проект так:** `C:\dev\retroconsole` + WSL JDK — Gradle лезет в `/mnt/c` и ползёт.

## Тест issue #4 (log spam)

Смотри лог сервера при загрузке ROM — не должно быть `[core] %s: %s`.

## Linux deps (если HW-ядра)

```bash
sudo apt install -y libegl1 libgl1 mesa-utils
```
