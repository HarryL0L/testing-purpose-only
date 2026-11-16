#!/usr/bin/env python3
"""Owners and extended attributes of an erofs image, recorded on its extracted copy.

    erofsmeta.py IMAGE DEST

Android's fsck.erofs extracts files but can't set extended attributes (it's
built without lsetxattr), and an app can't chown. Native mode's libc keeps
both in user xattrs instead (native_root.cpp): user.nmode.owner (uid, gid)
and user.nmode.<name> for security.*/trusted.* attributes (SELinux labels,
file capabilities). This walks the image's metadata (no file data, so no
decompression) and writes those on DEST's files. Symlinks can't carry user
xattrs and are skipped.
"""
import os
import struct
import sys

EROFS_MAGIC = 0xE0F5E1E2
PREFIXES = {1: "user.", 4: "trusted.", 6: "security."}
FLAT_PLAIN, FLAT_INLINE = 0, 2


class Erofs:
    def __init__(self, path):
        self.f = open(path, "rb")
        sb = self.read(1024, 128)
        if struct.unpack_from("<I", sb, 0)[0] != EROFS_MAGIC:
            raise ValueError("not erofs: " + path)
        self.blkszbits = sb[12]
        self.bs = 1 << self.blkszbits
        self.root_nid = struct.unpack_from("<H", sb, 14)[0]
        self.meta_blkaddr = struct.unpack_from("<I", sb, 40)[0]
        self.xattr_blkaddr = struct.unpack_from("<I", sb, 44)[0]
        # 48-byte superblock extension: 64-bit root nid when the feature is set
        self.feature_incompat = struct.unpack_from("<I", sb, 80)[0]

    def read(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def inode(self, nid):
        off = self.meta_blkaddr * self.bs + nid * 32
        raw = self.read(off, 64)
        fmt = struct.unpack_from("<H", raw, 0)[0]
        extended = fmt & 1
        layout = (fmt >> 1) & 7
        xattr_icount = struct.unpack_from("<H", raw, 2)[0]
        mode = struct.unpack_from("<H", raw, 4)[0]
        if extended:
            size = struct.unpack_from("<Q", raw, 8)[0]
            raw_blkaddr = struct.unpack_from("<I", raw, 16)[0]
            uid, gid = struct.unpack_from("<II", raw, 24)
            isize = 64
        else:
            size = struct.unpack_from("<I", raw, 8)[0]
            raw_blkaddr = struct.unpack_from("<I", raw, 16)[0]
            uid, gid = struct.unpack_from("<HH", raw, 24)
            isize = 32
        xsize = 0 if xattr_icount == 0 else 12 + (xattr_icount - 1) * 4
        return dict(off=off, isize=isize, layout=layout, mode=mode, size=size,
                    blkaddr=raw_blkaddr, uid=uid, gid=gid, xsize=xsize)

    def shared(self, sid):
        """A shared xattr entry (most SELinux labels are shared), cached."""
        cache = self.__dict__.setdefault("_shared", {})
        if sid not in cache:
            off = self.xattr_blkaddr * self.bs + sid * 4
            head = self.read(off, 4)
            name_len, _idx, vsize = struct.unpack_from("<BBH", head, 0)
            cache[sid] = self.read(off, 4 + name_len + vsize)
        return cache[sid]

    def xattrs(self, ino):
        out = {}
        if ino["xsize"] == 0:
            return out
        base = ino["off"] + ino["isize"]
        body = self.read(base, ino["xsize"])
        shared_count = body[4]
        pos = 12
        entries = []
        for i in range(shared_count):
            sid = struct.unpack_from("<I", body, pos)[0]
            pos += 4
            entries.append(self.shared(sid))
        while pos + 4 <= len(body):
            entries.append(body[pos:])
            name_len, idx, vsize = struct.unpack_from("<BBH", body, pos)
            pos += (4 + name_len + vsize + 3) & ~3
        for e in entries:
            if len(e) < 4:
                continue
            name_len, idx, vsize = struct.unpack_from("<BBH", e, 0)
            if idx not in PREFIXES or 4 + name_len + vsize > len(e):
                continue
            name = PREFIXES[idx] + e[4:4 + name_len].decode("utf-8", "replace")
            out[name] = bytes(e[4 + name_len:4 + name_len + vsize])
        return out

    def dir_data(self, ino):
        size, layout = ino["size"], ino["layout"]
        if layout == FLAT_PLAIN:
            return self.read(ino["blkaddr"] * self.bs, size)
        if layout == FLAT_INLINE:
            nblocks = size >> self.blkszbits  # whole blocks, then the tail inline
            head = self.read(ino["blkaddr"] * self.bs, nblocks * self.bs) if nblocks else b""
            tail_len = size - nblocks * self.bs
            tail_off = ino["off"] + ino["isize"] + ino["xsize"]
            return head + self.read(tail_off, tail_len)
        raise ValueError("unsupported directory layout %d" % layout)

    def entries(self, ino):
        data = self.dir_data(ino)
        for blk in range(0, len(data), self.bs):
            b = data[blk:blk + self.bs]
            if len(b) < 12:
                break
            first_nameoff = struct.unpack_from("<H", b, 8)[0]
            n = first_nameoff // 12
            for i in range(n):
                nid, nameoff, ftype = struct.unpack_from("<QHB", b, i * 12)
                end = struct.unpack_from("<H", b, (i + 1) * 12 + 8)[0] if i + 1 < n else len(b)
                name = b[nameoff:end].split(b"\0", 1)[0].decode("utf-8", "surrogateescape")
                if name not in (".", ".."):
                    yield name, nid

    def walk(self, nid=None, rel=""):
        nid = self.root_nid if nid is None else nid
        ino = self.inode(nid)
        yield rel, ino
        if (ino["mode"] & 0o170000) == 0o040000:
            for name, child in self.entries(ino):
                yield from self.walk(child, rel + "/" + name)


def record(image, dest):
    fs = Erofs(image)
    n = 0
    for rel, ino in fs.walk():
        if (ino["mode"] & 0o170000) == 0o120000:
            continue  # symlink: no user xattrs
        path = dest + rel
        if not os.path.exists(path):
            continue
        try:
            os.setxattr(path, "user.nmode.owner", struct.pack("<II", ino["uid"], ino["gid"]),
                        follow_symlinks=False)
            for name, value in fs.xattrs(ino).items():
                if name.startswith(("security.", "trusted.")):
                    os.setxattr(path, "user.nmode." + name, value, follow_symlinks=False)
            n += 1
        except OSError:
            pass
    return n


def main():
    print("recorded %d files" % record(sys.argv[1], sys.argv[2].rstrip("/")))


if __name__ == "__main__":
    main()
