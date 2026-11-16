#!/usr/bin/env python3
"""Extract a read-only ext4 image (as Android builds them) to a directory.

    ext4x.py IMAGE DEST

Pure Python, no root: regular files, directories and symlinks with their
permission bits; with --meta (a17root), owners and security./trusted.
attributes are recorded as native mode's libc keeps them (user.nmode.*).
Handles extents and block maps; htree directories read as linear ones. Used to unpack APEX payloads on the phone,
where there is no debugfs.
"""
import os
import stat
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from erofsmeta import lsetxattr  # noqa: E402


class Ext4:
    def __init__(self, path):
        self.f = open(path, "rb")
        sb = self.read(1024, 1024)
        if struct.unpack_from("<H", sb, 0x38)[0] != 0xEF53:
            raise ValueError("not ext4: " + path)
        self.inodes_per_group = struct.unpack_from("<I", sb, 0x28)[0]
        self.block_size = 1024 << struct.unpack_from("<I", sb, 0x18)[0]
        self.first_data_block = struct.unpack_from("<I", sb, 0x14)[0]
        self.inode_size = struct.unpack_from("<H", sb, 0x58)[0]
        incompat = struct.unpack_from("<I", sb, 0x60)[0]
        self.is64 = bool(incompat & 0x80)
        self.desc_size = struct.unpack_from("<H", sb, 0xFE)[0] if self.is64 else 32
        if self.desc_size < 32:
            self.desc_size = 32
        self.gdt = (self.first_data_block + 1) * self.block_size
        self._tables = {}

    def read(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def inode_table(self, group):
        if group not in self._tables:
            d = self.read(self.gdt + group * self.desc_size, self.desc_size)
            lo = struct.unpack_from("<I", d, 8)[0]
            hi = struct.unpack_from("<I", d, 0x28)[0] if self.is64 and self.desc_size >= 64 else 0
            self._tables[group] = (hi << 32) | lo
        return self._tables[group]

    def inode(self, ino):
        group, index = divmod(ino - 1, self.inodes_per_group)
        off = self.inode_table(group) * self.block_size + index * self.inode_size
        return self.read(off, self.inode_size)

    @staticmethod
    def size(raw):
        return struct.unpack_from("<I", raw, 4)[0] | (struct.unpack_from("<I", raw, 0x6C)[0] << 32)

    def blocks(self, raw):
        """[(logical block, physical block, count)] of an inode."""
        flags = struct.unpack_from("<I", raw, 0x20)[0]
        iblock = raw[0x28:0x28 + 60]
        if flags & 0x80000:  # extents
            out = []
            self._extents(iblock, out)
            return out
        out = []
        ptrs = struct.unpack("<15I", iblock)
        nblocks = (self.size(raw) + self.block_size - 1) // self.block_size
        per = self.block_size // 4
        logical = 0

        def add(p):
            nonlocal logical
            if logical < nblocks:
                if p:
                    out.append((logical, p, 1))
                logical += 1

        def indirect(p, depth):
            nonlocal logical
            if p == 0:
                logical += per ** depth
                return
            entries = struct.unpack("<%dI" % per, self.read(p * self.block_size, self.block_size))
            for e in entries:
                if logical >= nblocks:
                    return
                if depth == 1:
                    add(e)
                else:
                    indirect(e, depth - 1)

        for p in ptrs[:12]:
            add(p)
        for depth, p in ((1, ptrs[12]), (2, ptrs[13]), (3, ptrs[14])):
            if logical < nblocks:
                indirect(p, depth)
        return out

    def _extents(self, node, out):
        magic, entries, _max, depth = struct.unpack_from("<HHHH", node, 0)
        if magic != 0xF30A:
            raise ValueError("bad extent header")
        for i in range(entries):
            e = node[12 + i * 12:24 + i * 12]
            if depth == 0:
                lblk, length, hi, lo = struct.unpack("<IHHI", e)
                if length > 32768:  # uninitialized: reads as zeros
                    continue
                out.append((lblk, (hi << 32) | lo, length))
            else:
                _lblk, lo, hi = struct.unpack_from("<IIH", e)
                self._extents(self.read(((hi << 32) | lo) * self.block_size, self.block_size), out)

    def data(self, raw):
        size = self.size(raw)
        flags = struct.unpack_from("<I", raw, 0x20)[0]
        if flags & 0x10000000:  # inline data: the first 60 bytes live in i_block
            return raw[0x28:0x28 + min(size, 60)]
        buf = bytearray(size)
        bs = self.block_size
        for lblk, pblk, count in self.blocks(raw):
            start = lblk * bs
            if start >= size:
                continue
            n = min(count * bs, size - start)
            buf[start:start + n] = self.read(pblk * bs, n)
        return bytes(buf)

    def copy_to(self, raw, path):
        size = self.size(raw)
        flags = struct.unpack_from("<I", raw, 0x20)[0]
        with open(path, "wb") as out:
            if flags & 0x10000000:
                out.write(raw[0x28:0x28 + min(size, 60)])
                return
            bs = self.block_size
            for lblk, pblk, count in sorted(self.blocks(raw)):
                start = lblk * bs
                if start >= size:
                    continue
                n = min(count * bs, size - start)
                out.seek(start)
                left, src = n, pblk * bs
                while left:
                    chunk = self.read(src, min(left, 1 << 22))
                    out.write(chunk)
                    left -= len(chunk)
                    src += len(chunk)
            out.truncate(size)

    def entries(self, raw):
        d = self.data(raw)
        pos = 0
        while pos + 8 <= len(d):
            ino, rec_len, name_len, _ft = struct.unpack_from("<IHBB", d, pos)
            if rec_len < 8:
                break
            if ino:
                name = d[pos + 8:pos + 8 + name_len].decode("utf-8", "surrogateescape")
                if name not in (".", ".."):
                    yield name, ino
            pos += rec_len

    PREFIXES = {1: "user.", 4: "trusted.", 6: "security."}

    def owner(self, raw):
        uid = struct.unpack_from("<H", raw, 0x2)[0] | (struct.unpack_from("<H", raw, 0x78)[0] << 16)
        gid = struct.unpack_from("<H", raw, 0x18)[0] | (struct.unpack_from("<H", raw, 0x7A)[0] << 16)
        return uid, gid

    def _xattr_entries(self, buf, first, value_base, out):
        pos = first
        while pos + 16 <= len(buf):
            name_len, idx, voff, vinum, vsize = struct.unpack_from("<BBHII", buf, pos)
            if name_len == 0 and idx == 0 and voff == 0 and vinum == 0:
                break
            name = buf[pos + 16:pos + 16 + name_len].decode("utf-8", "replace")
            if idx in self.PREFIXES and vinum == 0:
                v = buf[value_base + voff:value_base + voff + vsize]
                out[self.PREFIXES[idx] + name] = bytes(v)
            pos += (16 + name_len + 3) & ~3

    def xattrs(self, raw):
        """security./trusted./user. attributes, in the inode and its xattr block."""
        out = {}
        if len(raw) > 0x82:
            extra = struct.unpack_from("<H", raw, 0x80)[0]
            base = 128 + extra
            if base + 4 <= len(raw) and struct.unpack_from("<I", raw, base)[0] == 0xEA020000:
                self._xattr_entries(raw, base + 4, base + 4, out)
        acl = struct.unpack_from("<I", raw, 0x68)[0] | (struct.unpack_from("<H", raw, 0x76)[0] << 32)
        if acl:
            blk = self.read(acl * self.block_size, self.block_size)
            if struct.unpack_from("<I", blk, 0)[0] == 0xEA020000:
                self._xattr_entries(blk, 32, 0, out)
        return out

    def record(self, raw, path):
        """Native mode's libc records of the owner and labels (see erofsmeta.py)."""
        if not self.keep_meta:
            return
        try:
            lsetxattr(path, "user.nmode.owner", struct.pack("<II", *self.owner(raw)))
            for name, value in self.xattrs(raw).items():
                if name.startswith(("security.", "trusted.")):
                    lsetxattr(path, "user.nmode." + name, value)
        except OSError:
            pass

    keep_meta = False

    def extract(self, dest, ino=2):
        raw = self.inode(ino)
        os.makedirs(dest, exist_ok=True)
        for name, child in self.entries(raw):
            if name == "lost+found" and ino == 2:
                continue
            craw = self.inode(child)
            mode = struct.unpack_from("<H", craw, 0)[0]
            p = os.path.join(dest, name)
            if stat.S_ISDIR(mode):
                self.extract(p, child)
                os.chmod(p, stat.S_IMODE(mode) | 0o700)
                self.record(craw, p)
            elif stat.S_ISLNK(mode):
                size = self.size(craw)
                flags = struct.unpack_from("<I", craw, 0x20)[0]
                if size < 60 and not flags & 0x80000:
                    target = craw[0x28:0x28 + size]
                else:
                    target = self.data(craw)
                if os.path.lexists(p):
                    os.remove(p)
                os.symlink(target.decode("utf-8", "surrogateescape"), p)
            elif stat.S_ISREG(mode):
                self.copy_to(craw, p)
                os.chmod(p, stat.S_IMODE(mode) | 0o600)
                self.record(craw, p)


def main():
    fs = Ext4(sys.argv[1])
    fs.keep_meta = "--meta" in sys.argv[3:]
    fs.extract(sys.argv[2])


if __name__ == "__main__":
    main()
