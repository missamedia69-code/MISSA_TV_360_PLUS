#!/usr/bin/env python3
"""Pack ngdevkit MAME chip files as an unencrypted TerraOnion NEO v1 image.

Layout cross-checked against Geolith geo_neo.c and lithogen neowrite.c.
Does NOT turn arbitrary games into Neo Geo programs or decrypt encrypted ROMs.
"""
import argparse
import hashlib
import struct
from pathlib import Path

NGH = 1987  # unique prototype ID, configured in the ngdevkit linker too
HEADER = 4096


def pack(chips: Path, prefix: str, output: Path):
    def load(ext):
        p = chips / f"{prefix}-{ext}"
        b = p.read_bytes()
        if not b:
            raise ValueError(f"empty ROM chip: {p}")
        return b

    p, s, m, v = (load("p1.p1"), load("s1.s1"), load("m1.m1"), load("v1.v1"))
    c1, c2 = load("c1.c1"), load("c2.c2")
    if len(c1) != len(c2):
        raise ValueError("C1 and C2 must be equal-sized and byte-interleaved")
    if len(p) < 0x10A or int.from_bytes(p[0x108:0x10A], "little") != NGH:
        raise ValueError("P-ROM NGH mismatch: check the native linker ID before packaging")
    for name, data in (("P", p), ("S", s), ("M", m), ("V", v)):
        if len(data) % 2:
            raise ValueError(f"{name} size must be even")
    if len(p) + len(s) + len(m) + len(v) + 2 * len(c1) > 160 * 1024 * 1024:
        raise ValueError("ROM exceeds the app's 160 MiB limit")

    header = bytearray(HEADER)
    header[:4] = b"NEO\x01"
    struct.pack_into("<10I", header, 4, len(p), len(s), len(m), len(v), 0,
                     2 * len(c1), 2026, 10, 0, NGH)
    header[44:77] = b"Bomber Plus - Homebrew".ljust(33, b"\0")
    header[77:94] = b"MISSA TV".ljust(17, b"\0")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as out:
        out.write(header)
        out.write(p)  # raw cartridge file byte order (Geolith swaps on load)
        out.write(s)
        out.write(m)
        out.write(v)
        for start in range(0, len(c1), 65536):
            left, right = c1[start:start + 65536], c2[start:start + 65536]
            interleaved = bytearray(2 * len(left))
            interleaved[0::2], interleaved[1::2] = left, right
            out.write(interleaved)
    expected = HEADER + len(p) + len(s) + len(m) + len(v) + 2 * len(c1)
    if output.stat().st_size != expected:
        raise ValueError("NEO size mismatch")
    print(f"{output}: {expected} bytes SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("chips", type=Path, help="directory containing ngdevkit ROM chips")
    ap.add_argument("output", type=Path)
    ap.add_argument("--prefix", default="bomberplus")
    args = ap.parse_args()
    pack(args.chips, args.prefix, args.output)
