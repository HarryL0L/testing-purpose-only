package io.github.harryl0l.a17;

import android.system.Os;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owners and extended attributes of an erofs image, recorded on its
 * extracted copy (tools/erofsmeta.py). The image's fsck.erofs extracts files
 * but can't set xattrs, and an app can't chown: native mode's libc keeps both
 * in user xattrs instead, user.nmode.owner (uid, gid) and user.nmode.<name>
 * for security.* and trusted.* (SELinux labels, file capabilities). Only
 * metadata is read (no file data, so no decompression).
 */
final class Erofs implements AutoCloseable {
    static final int MAGIC = 0xE0F5E1E2;
    private static final String[] PREFIXES = new String[8];
    static {
        PREFIXES[1] = "user.";
        PREFIXES[4] = "trusted.";
        PREFIXES[6] = "security.";
    }
    private static final int FLAT_PLAIN = 0, FLAT_INLINE = 2;

    private final RandomAccessFile f;
    private final int blkszbits, bs;
    private final long rootNid, metaBlkaddr, xattrBlkaddr;
    private final Map<Long, byte[]> shared = new HashMap<>();

    static final class Inode {
        long off, size, blkaddr;
        int isize, layout, mode, uid, gid, xsize;
        boolean isDir() { return (mode & 0170000) == 0040000; }
        boolean isLink() { return (mode & 0170000) == 0120000; }
    }

    Erofs(File path) throws IOException {
        f = new RandomAccessFile(path, "r");
        ByteBuffer sb = read(1024, 128);
        if (sb.getInt(0) != MAGIC) {
            f.close();
            throw new IOException("not erofs: " + path);
        }
        blkszbits = sb.get(12) & 0xff;
        bs = 1 << blkszbits;
        rootNid = sb.getShort(14) & 0xffff;
        metaBlkaddr = sb.getInt(40) & 0xffffffffL;
        xattrBlkaddr = sb.getInt(44) & 0xffffffffL;
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
        return ByteBuffer.wrap(b, 0, got).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] bytes(ByteBuffer b, int from, int to) {
        byte[] out = new byte[Math.max(0, Math.min(to, b.limit()) - from)];
        for (int i = 0; i < out.length; i++) out[i] = b.get(from + i);
        return out;
    }

    Inode inode(long nid) throws IOException {
        Inode i = new Inode();
        i.off = metaBlkaddr * bs + nid * 32;
        ByteBuffer raw = read(i.off, 64);
        int fmt = raw.getShort(0) & 0xffff;
        boolean extended = (fmt & 1) != 0;
        i.layout = (fmt >> 1) & 7;
        int xattrIcount = raw.getShort(2) & 0xffff;
        i.mode = raw.getShort(4) & 0xffff;
        i.blkaddr = raw.getInt(16) & 0xffffffffL;
        if (extended) {
            i.size = raw.getLong(8);
            i.uid = raw.getInt(24);
            i.gid = raw.getInt(28);
            i.isize = 64;
        } else {
            i.size = raw.getInt(8) & 0xffffffffL;
            i.uid = raw.getShort(24) & 0xffff;
            i.gid = raw.getShort(26) & 0xffff;
            i.isize = 32;
        }
        i.xsize = xattrIcount == 0 ? 0 : 12 + (xattrIcount - 1) * 4;
        return i;
    }

    /** A shared xattr entry (most SELinux labels are shared), cached. */
    private byte[] shared(long sid) throws IOException {
        byte[] e = shared.get(sid);
        if (e == null) {
            long off = xattrBlkaddr * bs + sid * 4;
            ByteBuffer head = read(off, 4);
            int nameLen = head.get(0) & 0xff, vsize = head.getShort(2) & 0xffff;
            e = bytes(read(off, 4 + nameLen + vsize), 0, 4 + nameLen + vsize);
            shared.put(sid, e);
        }
        return e;
    }

    Map<String, byte[]> xattrs(Inode ino) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (ino.xsize == 0) return out;
        ByteBuffer body = read(ino.off + ino.isize, ino.xsize);
        int sharedCount = body.get(4) & 0xff;
        int pos = 12;
        List<byte[]> entries = new ArrayList<>();
        for (int i = 0; i < sharedCount && pos + 4 <= body.limit(); i++) {
            entries.add(shared(body.getInt(pos) & 0xffffffffL));
            pos += 4;
        }
        while (pos + 4 <= body.limit()) {
            entries.add(bytes(body, pos, body.limit()));
            int nameLen = body.get(pos) & 0xff, vsize = body.getShort(pos + 2) & 0xffff;
            pos += (4 + nameLen + vsize + 3) & ~3;
        }
        for (byte[] e : entries) {
            if (e.length < 4) continue;
            int nameLen = e[0] & 0xff, idx = e[1] & 0xff, vsize = (e[2] & 0xff) | (e[3] & 0xff) << 8;
            if (idx >= PREFIXES.length || PREFIXES[idx] == null || 4 + nameLen + vsize > e.length) continue;
            String name = PREFIXES[idx] + new String(e, 4, nameLen, StandardCharsets.UTF_8);
            byte[] v = new byte[vsize];
            System.arraycopy(e, 4 + nameLen, v, 0, vsize);
            out.put(name, v);  // a later entry of one name wins, as in erofsmeta.py
        }
        return out;
    }

    private ByteBuffer dirData(Inode ino) throws IOException {
        int size = (int) ino.size;
        if (ino.layout == FLAT_PLAIN) return read(ino.blkaddr * bs, size);
        if (ino.layout == FLAT_INLINE) {
            int nblocks = size >> blkszbits;  // whole blocks, then the tail inline
            ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            if (nblocks > 0) out.put(read(ino.blkaddr * bs, nblocks * bs));
            out.put(read(ino.off + ino.isize + ino.xsize, size - nblocks * bs));
            out.flip();
            return out;
        }
        throw new IOException("unsupported directory layout " + ino.layout);
    }

    /** A directory's entries: name -> nid, in on-disk order. */
    Map<String, Long> entries(Inode ino) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        ByteBuffer data = dirData(ino);
        for (int blk = 0; blk < data.limit(); blk += bs) {
            int blen = Math.min(bs, data.limit() - blk);
            if (blen < 12) break;
            ByteBuffer b = ((ByteBuffer) data.duplicate().position(blk).limit(blk + blen)).slice()
                    .order(ByteOrder.LITTLE_ENDIAN);
            int n = (b.getShort(8) & 0xffff) / 12;
            for (int i = 0; i < n; i++) {
                long nid = b.getLong(i * 12);
                int nameoff = b.getShort(i * 12 + 8) & 0xffff;
                int end = i + 1 < n ? b.getShort((i + 1) * 12 + 8) & 0xffff : blen;
                int len = 0;
                while (nameoff + len < end && b.get(nameoff + len) != 0) len++;
                String name = new String(bytes(b, nameoff, nameoff + len), StandardCharsets.UTF_8);
                if (!name.equals(".") && !name.equals("..")) out.put(name, nid);
            }
        }
        return out;
    }

    interface Visitor { void visit(String rel, Inode ino) throws IOException; }

    /** Every inode under the root, rel "" for the root itself, then "/a", "/a/b", ... */
    void walk(Visitor v) throws IOException { walk(rootNid, "", v); }

    private void walk(long nid, String rel, Visitor v) throws IOException {
        Inode ino = inode(nid);
        v.visit(rel, ino);
        if (ino.isDir()) {
            for (Map.Entry<String, Long> e : entries(ino).entrySet()) walk(e.getValue(), rel + "/" + e.getKey(), v);
        }
    }

    /** Records the image's owners and labels on its extracted copy at dest. */
    static int record(File image, String dest) throws IOException {
        int[] n = {0};
        try (Erofs fs = new Erofs(image)) {
            fs.walk((rel, ino) -> {
                if (ino.isLink()) return;  // symlinks take no user xattrs
                String path = dest + rel;
                if (!new File(path).exists()) return;
                if (record(path, ino.uid, ino.gid, fs.xattrs(ino))) n[0]++;
            });
        }
        return n[0];
    }

    /**
     * user.nmode.owner and user.nmode.<security./trusted. name> on one file;
     * false if the file refuses them (all of them skipped from there, as
     * erofsmeta.py does).
     */
    static boolean record(String path, int uid, int gid, Map<String, byte[]> xattrs) {
        try {
            byte[] owner = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(uid).putInt(gid).array();
            Os.setxattr(path, "user.nmode.owner", owner, 0);
            for (Map.Entry<String, byte[]> e : xattrs.entrySet()) {
                if (e.getKey().startsWith("security.") || e.getKey().startsWith("trusted.")) {
                    Os.setxattr(path, "user.nmode." + e.getKey(), e.getValue(), 0);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
