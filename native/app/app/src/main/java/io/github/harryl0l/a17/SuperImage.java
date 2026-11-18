package io.github.harryl0l.a17;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Partitions streamed out of super.img (sparse or raw) as it comes out of
 * the image zip: one pass, no copy of super (tools/a17root.py's
 * extract_partitions).
 */
final class SuperImage {
    private static final int META = 1 << 20;  // LP geometry and metadata live in the first MiB

    /** A partition's linear extent in super: offset (or -1: zeros) and length, in bytes. */
    private static final class Extent {
        final long superOff, length, partOff;
        Extent(long superOff, long length, long partOff) {
            this.superOff = superOff;
            this.length = length;
            this.partOff = partOff;
        }
    }

    /**
     * Writes partitions {name: file} from super.img's bytes; ones super lacks
     * are skipped (their file removed). Returns name -> size of those written.
     */
    static Map<String, Long> extract(InputStream superImg, Map<String, File> wanted) throws IOException {
        byte[] meta = new byte[META];
        Map<String, List<Extent>> layouts = null;
        Map<String, Long> totals = new HashMap<>();
        Map<String, RandomAccessFile> outs = new HashMap<>();
        try {
            for (Map.Entry<String, File> e : wanted.entrySet()) {
                outs.put(e.getKey(), new RandomAccessFile(e.getValue(), "rw"));
                outs.get(e.getKey()).setLength(0);
            }
            Chunks in = new Chunks(new DataInputStream(superImg));
            while (in.next()) {
                long off = in.off, n = in.len;
                if (layouts == null) {
                    if (off < META && in.data != null) in.copy(meta, (int) off, (int) Math.min(n, META - off));
                    if (off + n >= META) {
                        layouts = new HashMap<>();
                        for (String name : wanted.keySet()) {
                            List<long[]> ext = lpExtents(meta, name);
                            if (ext == null) continue;
                            List<Extent> layout = new ArrayList<>();
                            long pos = 0;
                            for (long[] x : ext) {
                                layout.add(new Extent(x[0], x[1], pos));
                                pos += x[1];
                            }
                            layouts.put(name, layout);
                            totals.put(name, pos);
                        }
                    }
                }
                if (layouts == null || in.data == null) continue;
                for (Map.Entry<String, List<Extent>> e : layouts.entrySet()) {
                    RandomAccessFile out = outs.get(e.getKey());
                    for (Extent x : e.getValue()) {
                        if (x.superOff < 0) continue;
                        long lo = Math.max(off, x.superOff), hi = Math.min(off + n, x.superOff + x.length);
                        if (lo < hi) {
                            out.seek(x.partOff + lo - x.superOff);
                            in.write(out, lo - off, hi - lo);
                        }
                    }
                }
            }
        } finally {
            for (Map.Entry<String, RandomAccessFile> e : outs.entrySet()) {
                Long total = totals.get(e.getKey());
                if (total != null) e.getValue().setLength(total);
                e.getValue().close();
            }
        }
        for (Map.Entry<String, File> e : wanted.entrySet()) {
            if (!totals.containsKey(e.getKey())) e.getValue().delete();
        }
        return totals;
    }

    /**
     * Linear extents {offset or -1, length} of partition `name` from super's
     * first MiB (LP geometry at 4096, primary metadata at 12288), or null.
     */
    static List<long[]> lpExtents(byte[] meta, String name) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(meta).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(4096) != 0x616C4467) throw new IOException("no LP geometry in super.img");
        int h = 12288;
        if (b.getInt(h) != 0x414C5030) throw new IOException("no LP metadata header");
        int tables = h + b.getInt(h + 8);
        // table descriptors: offset, count, entry size
        int pOff = b.getInt(h + 80), pNum = b.getInt(h + 84), pSize = b.getInt(h + 88);
        int eOff = b.getInt(h + 92), eSize = b.getInt(h + 100);
        for (int i = 0; i < pNum; i++) {
            int e = tables + pOff + i * pSize;
            int len = 0;
            while (len < 36 && meta[e + len] != 0) len++;
            if (!new String(meta, e, len, StandardCharsets.UTF_8).equals(name)) continue;
            int first = b.getInt(e + 40), num = b.getInt(e + 44);
            List<long[]> out = new ArrayList<>();
            for (int k = first; k < first + num; k++) {
                int x = tables + eOff + k * eSize;
                long sectors = b.getLong(x);
                int type = b.getInt(x + 8);
                long data = b.getLong(x + 12);
                out.add(new long[] {type == 0 ? data * 512 : -1, sectors * 512});
            }
            return out;
        }
        return null;
    }

    /** The logical bytes of a sparse or raw image, chunk by chunk. */
    private static final class Chunks {
        private final DataInputStream in;
        private boolean sparse, started;
        private int blockSize, chunksLeft, chunkHeader;
        private long rawLeft;     // of the current raw chunk
        private long next;        // logical offset of what comes next
        private byte[] fill;      // a non-zero fill chunk's pattern
        final byte[] buf = new byte[1 << 22];
        long off, len;
        byte[] data;              // null: zeros (or don't care)

        Chunks(DataInputStream in) { this.in = in; }

        private int u16(byte[] a, int o) { return (a[o] & 0xff) | (a[o + 1] & 0xff) << 8; }
        private int u32(byte[] a, int o) { return u16(a, o) | u16(a, o + 2) << 16; }

        boolean next() throws IOException {
            if (!started) {
                started = true;
                byte[] head = new byte[28];
                int got = readSome(head, 28);
                if (got == 28 && u32(head, 0) == 0xED26FF3A) {
                    sparse = true;
                    int fileHeader = u16(head, 8);
                    chunkHeader = u16(head, 10);
                    blockSize = u32(head, 12);
                    chunksLeft = u32(head, 20);
                    in.skipBytes(fileHeader - 28);
                } else {  // raw: these bytes are the first data
                    System.arraycopy(head, 0, buf, 0, got);
                    if (got == 0) return false;
                    off = 0;
                    len = got;
                    data = buf;
                    next = got;
                    return true;
                }
            }
            if (!sparse) {
                int got = readSome(buf, buf.length);
                if (got <= 0) return false;
                off = next;
                len = got;
                data = buf;
                next += got;
                return true;
            }
            while (rawLeft == 0) {
                fill = null;
                if (chunksLeft == 0) return false;
                chunksLeft--;
                byte[] ch = new byte[chunkHeader];
                in.readFully(ch);
                int type = u16(ch, 0);
                long n = (ch[4] & 0xffL | (ch[5] & 0xffL) << 8 | (ch[6] & 0xffL) << 16
                        | (ch[7] & 0xffL) << 24) * blockSize;
                if (type == 0xCAC1) {  // raw
                    rawLeft = n;
                } else if (type == 0xCAC2) {  // fill
                    byte[] pattern = new byte[4];
                    in.readFully(pattern);
                    off = next;
                    len = n;
                    next += n;
                    if (pattern[0] == 0 && pattern[1] == 0 && pattern[2] == 0 && pattern[3] == 0) {
                        data = null;
                    } else {
                        fill = pattern;
                        data = pattern;  // see write()
                    }
                    return true;
                } else if (type == 0xCAC3) {  // don't care
                    off = next;
                    len = n;
                    data = null;
                    next += n;
                    return true;
                } else if (type == 0xCAC4) {  // crc
                    in.readFully(new byte[4]);
                }
            }
            int k = (int) Math.min(rawLeft, buf.length);
            try {
                in.readFully(buf, 0, k);
            } catch (java.io.EOFException e) {
                throw new IOException("truncated sparse image");
            }
            off = next;
            len = k;
            data = buf;
            next += k;
            rawLeft -= k;
            return true;
        }

        /** Writes `n` bytes of the current chunk, from `from` within it. */
        void write(RandomAccessFile out, long from, long n) throws IOException {
            if (fill == null) {
                out.write(buf, (int) from, (int) n);
                return;
            }
            byte[] pat = new byte[1 << 16];
            for (int i = 0; i < pat.length; i++) pat[i] = fill[(int) ((from + i) & 3)];
            while (n > 0) {
                int k = (int) Math.min(n, pat.length);
                out.write(pat, 0, k);
                n -= k;
            }
        }

        /** The current chunk's first `n` bytes into `to` at `at`. */
        void copy(byte[] to, int at, int n) {
            for (int i = 0; i < n; i++) to[at + i] = fill != null ? fill[i & 3] : buf[i];
        }

        private int readSome(byte[] b, int n) throws IOException {
            int got = 0;
            while (got < n) {
                int r = in.read(b, got, n - got);
                if (r < 0) break;
                got += r;
            }
            return got;
        }
    }

    private SuperImage() {}
}
