#!/usr/bin/env python3
"""Validate PS2 BIOS files the same way PCSX2 BiosTools.cpp does."""
import os
import struct
import sys

def load_bios_version(path: str) -> tuple[bool, str]:
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        for _ in range(512 * 1024):
            rd = f.read(16)
            if len(rd) < 16:
                return False, "truncated before RESET"
            if rd[0:10].split(b"\0")[0] == b"RESET":
                break
        else:
            return False, "no RESET in first 512KB"

        file_offset = 0
        found_romver = False
        romver = b""
        while True:
            rd = f.read(16)
            if len(rd) < 16:
                break
            fname = rd[0:10].split(b"\0")[0]
            fsize = struct.unpack("<I", rd[12:16])[0]
            if fname == b"ROMVER":
                pos = f.tell()
                f.seek(file_offset)
                romver = f.read(14)
                f.seek(pos)
                found_romver = True
            if (fsize % 16) == 0:
                file_offset += fsize
            else:
                file_offset += (fsize + 0x10) & 0xFFFFFFF0

    if not found_romver:
        return False, "no ROMVER"
    return True, romver.decode("ascii", "replace")


def main() -> int:
    bios_dir = os.path.abspath(
        sys.argv[1] if len(sys.argv) > 1
        else "runs/client/config/retroconsole/system/pcsx2/bios"
    )
    print(f"BIOS dir: {bios_dir}")
    if not os.path.isdir(bios_dir):
        print("MISSING")
        return 1

    any_valid = False
    for name in sorted(os.listdir(bios_dir)):
        path = os.path.join(bios_dir, name)
        if not os.path.isfile(path) and not os.path.islink(path):
            continue
        real = os.path.realpath(path)
        size = os.path.getsize(real)
        kind = "symlink" if os.path.islink(path) else "file"
        print(f"{name} ({kind}) -> {real} [{size} bytes]")
        if size < 4 * 1024 * 1024 or size > 8 * 1024 * 1024:
            print("  skip: size out of 4-8MB")
            continue
        ok, info = load_bios_version(real)
        print(f"  PCSX2 IsBIOS: {ok} ({info})")
        any_valid = any_valid or ok
    return 0 if any_valid else 2

if __name__ == "__main__":
    raise SystemExit(main())
