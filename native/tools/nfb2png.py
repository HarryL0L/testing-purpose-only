#!/usr/bin/env python3
"""Save native mode's current screen (the frame file ncomposer writes) as PNG.

    python3 nfb2png.py [ROOT_DIR] [OUT.png]     defaults: ./root, screen.png
"""
import struct
import sys
import zlib

root = sys.argv[1] if len(sys.argv) > 1 else "root"
out = sys.argv[2] if len(sys.argv) > 2 else "screen.png"
with open(root + "/data/vendor/nfb", "rb") as f:
    d = f.read()
magic, w, h, stride, fmt, frame = struct.unpack_from("<6I", d, 0)
if magic != 0x3062666E:
    sys.exit("not a frame file")
px = d[64:]
rows = b"".join(b"\0" + px[y * stride * 4:y * stride * 4 + w * 4] for y in range(h))
if fmt == 5:  # BGRA -> RGBA
    a = bytearray(rows)
    for y in range(h):
        o = y * (w * 4 + 1) + 1
        a[o:o + w * 4:4], a[o + 2:o + w * 4:4] = a[o + 2:o + w * 4:4], a[o:o + w * 4:4]
    rows = bytes(a)


def chunk(t, b):
    return struct.pack(">I", len(b)) + t + b + struct.pack(">I", zlib.crc32(t + b) & 0xFFFFFFFF)


with open(out, "wb") as f:
    f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(rows, 6)) + chunk(b"IEND", b""))
print("%s: %dx%d, frame %d" % (out, w, h, frame))
