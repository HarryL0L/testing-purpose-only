# Minimal reader for Android dynamic partitions (super.img): lists or extracts partitions.
import struct, sys
f = open(sys.argv[1], 'rb')
def rd(o, n): f.seek(o); return f.read(n)
if struct.unpack('<I', rd(0,4))[0] == 0xed26ff3a:
    sys.exit("sparse image: unsparse it first")
g = rd(4096, 4096)
assert struct.unpack('<I', g[:4])[0] == 0x616c4467, "no LP geometry"
metamax, slots = struct.unpack('<II', g[40:48])
h = rd(12288, 256)
assert struct.unpack('<I', h[:4])[0] == 0x414c5030, "no LP header"
hsize = struct.unpack('<I', h[8:12])[0]
tsize = struct.unpack('<I', h[44:48])[0]
tables = rd(12288 + hsize, tsize)
def desc(o): return struct.unpack('<III', h[o:o+12])  # offset, num, entry_size
P, E = desc(80), desc(92)
parts = []
for i in range(P[1]):
    e = tables[P[0]+i*P[2]:P[0]+(i+1)*P[2]]
    name = e[:36].split(b'\0')[0].decode()
    attrs, first, num, grp = struct.unpack('<IIII', e[36:52])
    parts.append((name, first, num))
ext = []
for i in range(E[1]):
    e = tables[E[0]+i*E[2]:E[0]+(i+1)*E[2]]
    ns, tt, data, dev = struct.unpack('<QIQI', e[:24])
    ext.append((ns, tt, data))
want = sys.argv[2:]
for name, first, num in parts:
    size = sum(ext[j][0] for j in range(first, first+num)) * 512
    if not want:
        print(name, size); continue
    if name not in want: continue
    with open(name + '.img', 'wb') as out:
        for j in range(first, first+num):
            ns, tt, data = ext[j]
            if tt == 0:
                f.seek(data*512); left = ns*512
                while left:
                    b = f.read(min(left, 1<<22)); out.write(b); left -= len(b)
            else:
                out.write(b'\0' * ns * 512)
    print('wrote', name + '.img', size)
