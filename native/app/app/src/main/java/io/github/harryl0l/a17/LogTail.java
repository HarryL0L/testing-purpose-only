package io.github.harryl0l.a17;

import android.content.Context;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The end of Android 17's log (nlogd writes root/data/local/tmp/log.txt). */
final class LogTail {
    static File file(Context c) {
        return new File(MainActivity.Paths.a17(c), "root/data/local/tmp/log.txt");
    }

    static File bootLog(Context c) {
        return new File(MainActivity.Paths.a17(c), "nboot.log");
    }

    /** The last `bytes` of a file, as lines (the first, partial one dropped). */
    static List<String> lines(File f, int bytes) {
        List<String> out = new ArrayList<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            long start = Math.max(0, len - bytes);
            byte[] buf = new byte[(int) (len - start)];
            r.seek(start);
            r.readFully(buf);
            String[] parts = new String(buf, StandardCharsets.UTF_8).split("\n");
            for (int i = start > 0 ? 1 : 0; i < parts.length; i++) out.add(parts[i]);
        } catch (Exception e) {
            // no log yet
        }
        return out;
    }

    /** Errors, crashes and Android's own fatal messages. */
    static boolean problem(String line) {
        // SurfaceFlinger's fence merges (libc doesn't emulate them yet): noise
        if (line.contains("Fence: merge")) return false;
        return line.contains(" E ") || line.contains(" F ") || line.contains("FATAL")
                || line.contains("Runtime aborting") || line.contains("exited due to signal")
                || line.contains("CANNOT LINK");
    }

    private LogTail() {}
}
