# Android sparse image -> raw image
import struct, sys
f = open(sys.argv[1], 'rb'); out = open(sys.argv[2], 'wb')
magic, maj, mnr, fhs, chs, bs, tb, tc, _ = struct.unpack('<IHHHHIIII', f.read(28))
assert magic == 0xed26ff3a
f.seek(fhs)
for _ in range(tc):
    ct, _r, sz, total = struct.unpack('<HHII', f.read(chs))
    n = sz * bs
    if ct == 0xcac1:
        while n:
            b = f.read(min(n, 1 << 22)); out.write(b); n -= len(b)
    elif ct == 0xcac2:
        fill = f.read(4)
        out.write(fill * (n // 4)) if fill != b'\0\0\0\0' else out.seek(n, 1)
    elif ct == 0xcac3:
        out.seek(n, 1)
    elif ct == 0xcac4:
        f.read(4)
out.truncate(tb * bs)
