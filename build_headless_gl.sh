#!/bin/bash
# Build headless_gl.so — headless EGL/Mesa context for libretro HW rendering.
# Requires: libEGL-dev, libGL-dev (mesa)
#   sudo apt install libegl-dev libgl-dev

set -e
cd "$(dirname "$0")"

OUT="runs/client/config/retroconsole/cores/.libheadless_gl.so"
SRC="headless_gl.c"

echo "Compiling $SRC -> $OUT"
gcc -shared -fPIC -O2 -Wall -o "$OUT" "$SRC" -lEGL -lGL -ldl
strip "$OUT"
echo "Done: $(ls -lh "$OUT" | awk '{print $5}')"
