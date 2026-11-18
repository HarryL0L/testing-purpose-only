package io.github.harryl0l.a17;

import android.system.Os;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts a read-only ext4 image, as Android builds them (APEX payloads), to
 * a directory (tools/ext4x.py): regular files, directories and symlinks with
 * their permission bits, owners and security./trusted. attributes recorded
 * as native mode's libc keeps them (Erofs.record). Extents and block maps;
 * htree directories read as linear ones.
 */
final class Ext4 implements AutoCloseable {
    private static final String[] PREFIXES = new String[8];
    static {
        PREFIXES[1] = "user.";
        PREFIXES[4] = "trusted.";
        PREFIXES[6] = "security.";
    }

    private final RandomAccessFile f;
    private final int inodesPerGroup, blockSize, inodeSize, descSize;
    private final boolean is64;
    private final long gdt;
    private final Map<Long, Long> tables = new HashMap<>();

    static boolean isExt4(File path) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(path, "r")) {
            if (r.length() < 1024 + 0x3A) return false;
            r.seek(1024 + 0x38);
            return (r.read() | r.read() << 8) == 0xEF53;
        }
    }

    Ext4(File path) throws IOException {
        f = new RandomAccessFile(path, "r");
        ByteBuffer sb = read(1024, 1024);
        if ((sb.getShort(0x38) & 0xffff) != 0xEF53) {
            f.close();
            throw new IOException("not ext4: " + path);
        }
        inodesPerGroup = sb.getInt(0x28);
        blockSize = 1024 << sb.getInt(0x18);
        int firstDataBlock = sb.getInt(0x14);
        inodeSize = sb.getShort(0x58) & 0xffff;
        is64 = (sb.getInt(0x60) & 0x80) != 0;
        int ds = is64 ? sb.getShort(0xFE) & 0xffff : 32;
        descSize = Math.max(ds, 32);
        gdt = (long) (firstDataBlock + 1) * blockSize;
    }

    @Override public void close() throws IOException { f.close(); }

    private ByteBuffer read(long off, int n) throws IOException {
        byte[] b = new byte[n];
        f.seek(off);
        int got = 0;
        while (got < n) {
            int r = f.read(b, got, n - got);
            if (r < 0) break;
            got += r;
        }
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    private long inodeTable(long group) throws IOException {
        Long t = tables.get(group);
        if (t == null) {
            ByteBuffer d = read(gdt + group * descSize, descSize);
            long lo = d.getInt(8) & 0xffffffffL;
            long hi = is64 && descSize >= 64 ? d.getInt(0x28) & 0xffffffffL : 0;
            t = hi << 32 | lo;
            tables.put(group, t);
        }
        return t;
    }

    ByteBuffer inode(long ino) throws IOException {
        long group = (ino - 1) / inodesPerGroup, index = (ino - 1) % inodesPerGroup;
        return read(inodeTable(group) * blockSize + index * inodeSize, inodeSize);
    }

    static int mode(ByteBuffer raw) { return raw.getShort(0) & 0xffff; }
    private static int flags(ByteBuffer raw) { return raw.getInt(0x20); }

    static long size(ByteBuffer raw) {
        return (raw.getInt(4) & 0xffffffffL) | (raw.getInt(0x6C) & 0xffffffffL) << 32;
    }

    /** [logical block, physical block, count] of an inode. */
    private List<long[]> blocks(ByteBuffer raw) throws IOException {
        List<long[]> out = new ArrayList<>();
        ByteBuffer iblock = slice(raw, 0x28, 60);
        if ((flags(raw) & 0x80000) != 0) {  // extents
            extents(iblock, out);
            return out;
        }
        long nblocks = (size(raw) + blockSize - 1) / blockSize;
        long[] logical = {0};
        for (int i = 0; i < 12; i++) add(out, logical, nblocks, iblock.getInt(i * 4) & 0xffffffffL);
        for (int depth = 1; depth <= 3; depth++) {
            if (logical[0] < nblocks) indirect(out, logical, nblocks, iblock.getInt((11 + depth) * 4) & 0xffffffffL, depth);
        }
        return out;
    }

    private static void add(List<long[]> out, long[] logical, long nblocks, long p) {
        if (logical[0] < nblocks) {
            if (p != 0) out.add(new long[] {logical[0], p, 1});
            logical[0]++;
        }
    }

    private void indirect(List<long[]> out, long[] logical, long nblocks, long p, int depth) throws IOException {
        int per = blockSize / 4;
        if (p == 0) {
            logical[0] += (long) Math.pow(per, depth);
            return;
        }
        ByteBuffer entries = read(p * blockSize, blockSize);
        for (int i = 0; i < per; i++) {
            if (logical[0] >= nblocks) return;
            long e = entries.getInt(i * 4) & 0xffffffffL;
            if (depth == 1) add(out, logical, nblocks, e);
            else indirect(out, logical, nblocks, e, depth - 1);
        }
    }

    private void extents(ByteBuffer node, List<long[]> out) throws IOException {
        int magic = node.getShort(0) & 0xffff, entries = node.getShort(2) & 0xffff, depth = node.getShort(6) & 0xffff;
        if (magic != 0xF30A) throw new IOException("bad extent header");
        for (int i = 0; i < entries; i++) {
            int e = 12 + i * 12;
            if (depth == 0) {
                long lblk = node.getInt(e) & 0xffffffffL;
                int length = node.getShort(e + 4) & 0xffff;
                long hi = node.getShort(e + 6) & 0xffff, lo = node.getInt(e + 8) & 0xffffffffL;
                if (length > 32768) continue;  // uninitialized: reads as zeros
                out.add(new long[] {lblk, hi << 32 | lo, length});
            } else {
                long lo = node.getInt(e + 4) & 0xffffffffL, hi = node.getShort(e + 8) & 0xffff;
                extents(read((hi << 32 | lo) * blockSize, blockSize), out);
            }
        }
    }

    private static ByteBuffer slice(ByteBuffer b, int off, int len) {
        return ((ByteBuffer) b.duplicate().position(off).limit(off + len)).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A small file's (a directory's, a long symlink's) whole contents. */
    private byte[] data(ByteBuffer raw) throws IOException {
        int size = (int) size(raw);
        if ((flags(raw) & 0x10000000) != 0) {  // inline data: the first 60 bytes live in i_block
            byte[] b = new byte[Math.min(size, 60)];
            slice(raw, 0x28, 60).get(b);
            return b;
        }
        byte[] buf = new byte[size];
        for (long[] x : blocks(raw)) {
            long start = x[0] * blockSize;
            if (start >= size) continue;
            int n = (int) Math.min(x[2] * blockSize, size - start);
            f.seek(x[1] * blockSize);
            f.readFully(buf, (int) start, n);
        }
        return buf;
    }

    private void copyTo(ByteBuffer raw, File path) throws IOException {
        long size = size(raw);
        try (RandomAccessFile out = new RandomAccessFile(path, "rw")) {
            out.setLength(0);
            if ((flags(raw) & 0x10000000) != 0) {
                out.write(data(raw));
                return;
            }
            List<long[]> blocks = blocks(raw);
            blocks.sort(Comparator.comparingLong((long[] x) -> x[0]).thenComparingLong(x -> x[1]));
            byte[] buf = new byte[1 << 20];
            for (long[] x : blocks) {
                long start = x[0] * blockSize;
                if (start >= size) continue;
                long left = Math.min(x[2] * blockSize, size - start), src = x[1] * blockSize;
                out.seek(start);
                while (left > 0) {
                    int k = (int) Math.min(left, buf.length);
                    f.seek(src);
                    f.readFully(buf, 0, k);
                    out.write(buf, 0, k);
                    left -= k;
                    src += k;
                }
            }
            out.setLength(size);
        }
    }

    /** A directory's entries: name -> inode number, in on-disk order. */
    Map<String, Long> entries(ByteBuffer raw) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        ByteBuffer d = ByteBuffer.wrap(data(raw)).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 0;
        while (pos + 8 <= d.limit()) {
            long ino = d.getInt(pos) & 0xffffffffL;
            int recLen = d.getShort(pos + 4) & 0xffff, nameLen = d.get(pos + 6) & 0xff;
            if (recLen < 8) break;
            if (ino != 0 && pos + 8 + nameLen <= d.limit()) {
                String name = new String(d.array(), pos + 8, nameLen, StandardCharsets.UTF_8);
                if (!name.equals(".") && !name.equals("..")) out.put(name, ino);
            }
            pos += recLen;
        }
        return out;
    }

    static int[] owner(ByteBuffer raw) {
        int uid = (raw.getShort(0x2) & 0xffff) | (raw.getShort(0x78) & 0xffff) << 16;
        int gid = (raw.getShort(0x18) & 0xffff) | (raw.getShort(0x7A) & 0xffff) << 16;
        return new int[] {uid, gid};
    }

    private static void xattrEntries(ByteBuffer buf, int first, int valueBase, Map<String, byte[]> out) {
        int pos = first;
        while (pos + 16 <= buf.limit()) {
            int nameLen = buf.get(pos) & 0xff, idx = buf.get(pos + 1) & 0xff;
            int voff = buf.getShort(pos + 2) & 0xffff;
            int vinum = buf.getInt(pos + 4), vsize = buf.getInt(pos + 8);
            if (nameLen == 0 && idx == 0 && voff == 0 && vinum == 0) break;
            if (idx < PREFIXES.length && PREFIXES[idx] != null && vinum == 0) {
                byte[] name = new byte[Math.max(0, Math.min(nameLen, buf.limit() - pos - 16))];
                slice(buf, pos + 16, name.length).get(name);
                int from = Math.min(valueBase + voff, buf.limit());
                byte[] v = new byte[Math.max(0, Math.min(vsize, buf.limit() - from))];
                slice(buf, from, v.length).get(v);
                out.put(PREFIXES[idx] + new String(name, StandardCharsets.UTF_8), v);
            }
            pos += (16 + nameLen + 3) & ~3;
        }
    }

    /** security./trusted./user. attributes, in the inode and its xattr block. */
    Map<String, byte[]> xattrs(ByteBuffer raw) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (raw.limit() > 0x82) {
            int base = 128 + (raw.getShort(0x80) & 0xffff);
            if (base + 4 <= raw.limit() && raw.getInt(base) == 0xEA020000) {
                xattrEntries(raw, base + 4, base + 4, out);
            }
        }
        long acl = (raw.getInt(0x68) & 0xffffffffL) | (long) (raw.getShort(0x76) & 0xffff) << 32;
        if (acl != 0) {
            ByteBuffer blk = read(acl * blockSize, blockSize);
            if (blk.getInt(0) == 0xEA020000) xattrEntries(blk, 32, 0, out);
        }
        return out;
    }

    private void record(ByteBuffer raw, String path) throws IOException {
        int[] o = owner(raw);
        Erofs.record(path, o[0], o[1], xattrs(raw));
    }

    /** The whole image into dest, the root directory's own records included. */
    void extract(File dest) throws IOException {
        extract(dest, 2);
        record(inode(2), dest.getPath());
    }

    private void extract(File dest, long ino) throws IOException {
        ByteBuffer raw = inode(ino);
        dest.mkdirs();
        for (Map.Entry<String, Long> e : entries(raw).entrySet()) {
            String name = e.getKey();
            if (name.equals("lost+found") && ino == 2) continue;
            ByteBuffer craw = inode(e.getValue());
            int mode = mode(craw);
            File p = new File(dest, name);
            try {
                switch (mode & 0170000) {
                    case 0040000:
                        extract(p, e.getValue());
                        Os.chmod(p.getPath(), (mode & 07777) | 0700);
                        record(craw, p.getPath());
                        break;
                    case 0120000: {
                        long size = size(craw);
                        byte[] target;
                        if (size < 60 && (flags(craw) & 0x80000) == 0) {
                            target = new byte[(int) size];
                            slice(craw, 0x28, (int) size).get(target);
                        } else {
                            target = data(craw);
                        }
                        Files.deleteIfExists(p.toPath());
                        Os.symlink(new String(target, StandardCharsets.UTF_8), p.getPath());
                        break;
                    }
                    case 0100000:
                        copyTo(craw, p);
                        Os.chmod(p.getPath(), (mode & 07777) | 0600);
                        record(craw, p.getPath());
                        break;
                    default:
                        break;
                }
            } catch (android.system.ErrnoException x) {
                throw new IOException(p + ": " + x.getMessage(), x);
            }
        }
    }

    static boolean exists(File f) { return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS); }
}
