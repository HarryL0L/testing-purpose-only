package io.github.harryl0l.a17;

import android.content.Context;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Tells Android 17 where the phone's camera cutout is, as phones do: a static
 * overlay on the framework (config_mainBuiltInDisplayCutout) in its
 * /product/overlay. Its status bar then grows to the cutout's height and keeps
 * the clock and icons beside the hole. The overlay is a template
 * (tools/cutout-overlay) with placeholder strings, filled in here.
 */
final class CutoutOverlay {
    private static final String APK = "root/product/overlay/A17Cutout/A17Cutout.apk";
    private static final int SPEC_LEN = 240, RECT_LEN = 120;

    /**
     * The cutout at the top of the window, in its pixels (null removes the
     * overlay), and the phone's status bar height (0: Android 17's own).
     */
    static void write(Context c, File a17, Rect hole, int statusBar) {
        File dst = new File(a17, APK);
        if (hole == null || hole.isEmpty()) {
            dst.delete();
            return;
        }
        float cx = hole.exactCenterX(), cy = hole.exactCenterY();
        float r = Math.min(hole.width(), hole.height()) / 2f;
        String circle = String.format(java.util.Locale.ROOT,
                "M %.1f,%.1f A %.1f,%.1f 0 1 1 %.1f,%.1f A %.1f,%.1f 0 1 1 %.1f,%.1f Z @left",
                cx + r, cy, r, r, cx - r, cy, r, r, cx + r, cy);
        // The status bar keeps clear of this one: from the top edge down.
        String rect = String.format(java.util.Locale.ROOT, "M %d,0 h %d v %d h %d Z @left",
                hole.left, hole.width(), hole.bottom, -hole.width());
        // A notch rather than a hole (wide, from the top edge): the rectangle as is.
        String spec = hole.top <= 0 || hole.width() > 2 * hole.height() ? rect : circle;
        try {
            byte[] b = read(c.getAssets().open("cutout.apk"));
            replace(b, repeat('X', SPEC_LEN), spec);
            replace(b, repeat('Y', RECT_LEN), rect);
            // status_bar_height_portrait: as tall as the phone's own, at least
            // the cutout (a dimension in px: the value << 8).
            int px = Math.max(statusBar, hole.bottom);
            replaceDimen(b, 1234, px);
            fixCrc(b, "resources.arsc");
            dst.getParentFile().mkdirs();
            File tmp = new File(dst.getPath() + ".new");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(b);
            }
            tmp.setReadable(true, false);
            dst.getParentFile().setReadable(true, false);
            dst.getParentFile().setExecutable(true, false);
            if (!tmp.renameTo(dst)) android.util.Log.e("a17", "can't write " + dst);
        } catch (Exception e) {
            android.util.Log.e("a17", "cutout overlay", e);
        }
    }

    private static byte[] read(InputStream in) throws java.io.IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String repeat(char ch, int n) {
        char[] a = new char[n];
        java.util.Arrays.fill(a, ch);
        return new String(a);
    }

    private static void replace(byte[] b, String placeholder, String value) {
        byte[] ph = placeholder.getBytes(StandardCharsets.US_ASCII);
        byte[] v = value.getBytes(StandardCharsets.US_ASCII);
        if (v.length > ph.length) throw new IllegalArgumentException("cutout spec too long: " + value);
        outer:
        for (int i = 0; i + ph.length <= b.length; i++) {
            for (int k = 0; k < ph.length; k++) if (b[i + k] != ph[k]) continue outer;
            for (int k = 0; k < ph.length; k++) b[i + k] = k < v.length ? v[k] : (byte) ' ';
            return;
        }
        throw new IllegalStateException("no placeholder in the cutout template");
    }

    // A dimension Res_value {size 8, 0, TYPE_DIMENSION, data} in px.
    private static void replaceDimen(byte[] b, int placeholderPx, int px) {
        byte[] ph = {8, 0, 0, 5, 0, 0, 0, 0};
        ByteBuffer.wrap(ph, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(placeholderPx << 8);
        outer:
        for (int i = 0; i + ph.length <= b.length; i++) {
            for (int k = 0; k < ph.length; k++) if (b[i + k] != ph[k]) continue outer;
            ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(px << 8);
            return;
        }
        throw new IllegalStateException("no status bar height in the cutout template");
    }

    // The patched entry is stored (not compressed): its CRC in both zip headers.
    private static void fixCrc(byte[] b, String entry) {
        ByteBuffer z = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = b.length - 22;
        while (eocd >= 0 && z.getInt(eocd) != 0x06054b50) eocd--;
        int n = z.getShort(eocd + 10) & 0xffff, off = z.getInt(eocd + 16);
        for (int i = 0; i < n; i++) {
            int nl = z.getShort(off + 28) & 0xffff, el = z.getShort(off + 30) & 0xffff,
                    cl = z.getShort(off + 32) & 0xffff;
            String name = new String(b, off + 46, nl, StandardCharsets.UTF_8);
            if (name.equals(entry)) {
                int size = z.getInt(off + 20), lho = z.getInt(off + 42);
                int data = lho + 30 + (z.getShort(lho + 26) & 0xffff) + (z.getShort(lho + 28) & 0xffff);
                CRC32 crc = new CRC32();
                crc.update(b, data, size);
                z.putInt(off + 16, (int) crc.getValue());
                z.putInt(lho + 14, (int) crc.getValue());
                return;
            }
            off += 46 + nl + el + cl;
        }
    }

    private CutoutOverlay() {}
}
