package io.github.harryl0l.a17;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Android 17's log, all or problems only, with a Share button. */
public final class LogActivity extends Activity {
    private TextView text;
    private ScrollView scroll;
    private boolean problemsOnly = true;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(16, 64, 16, 16);
        LinearLayout bar = new LinearLayout(this);
        Button filter = new Button(this);
        Button refresh = new Button(this);
        Button share = new Button(this);
        filter.setText("Show all");
        refresh.setText("Refresh");
        share.setText("Share");
        bar.addView(filter);
        bar.addView(refresh);
        bar.addView(share);
        root.addView(bar);
        text = new TextView(this);
        text.setTextColor(Color.rgb(220, 220, 220));
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(9);
        text.setTextIsSelectable(true);
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.addView(text);
        scroll = new ScrollView(this);
        scroll.addView(h);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        filter.setOnClickListener(v -> {
            problemsOnly = !problemsOnly;
            filter.setText(problemsOnly ? "Show all" : "Problems only");
            load();
        });
        refresh.setOnClickListener(v -> load());
        share.setOnClickListener(v -> share());
        load();
    }

    private String collect(int bytes) {
        StringBuilder sb = new StringBuilder();
        // The screen Android 17 was given (MainActivity: size, cutout, status bar)
        for (String l : LogTail.lines(new File(MainActivity.Paths.a17(this), "screen.txt"), 1024)) {
            sb.append("== screen: ").append(l).append('\n');
        }
        // The app's own setup (SetupService), while there is one to show
        File setup = SetupService.logFile(this);
        if (setup.exists()) {
            sb.append("== a17-setup.log (end)\n");
            for (String l : LogTail.lines(setup, 16384)) sb.append(l).append('\n');
        }
        List<String> boot = LogTail.lines(LogTail.bootLog(this), 4096);
        sb.append("== nboot.log (end)\n");
        for (String l : boot) if (!l.contains("ctl.interface_start")) sb.append(l).append('\n');
        // The BPF loader's own output and the pins it made: system_server
        // stops at once when a networking map is missing.
        File root = new File(MainActivity.Paths.a17(this), "root");
        sb.append("== bpfloader.log (end)\n");
        for (String l : LogTail.lines(new File(root, "data/local/tmp/bpfloader.log"), 8192)) {
            sb.append(l).append('\n');
        }
        // The kernel log from boot: netbpfload and init-style programs write
        // there (servicemanager's SELinux noise left out).
        // The binder stand-in's own complaints (bad replies, full buffers).
        // Its routine lines (nodes made and freed) bury the few that matter:
        // those are picked out of the whole file.
        sb.append("== nbinder.log (problems)\n");
        List<String> nb = new java.util.ArrayList<>();
        for (String l : LogTail.lines(new File(root, "data/local/tmp/nbinder.log"), 16 << 20)) {
            if (l.contains("freed") || l.contains("new node") || l.contains("released")) continue;
            nb.add(l);
        }
        for (String l : nb.subList(Math.max(0, nb.size() - 80), nb.size())) sb.append(l).append('\n');
        // When system_server hangs its watchdog dumps every thread's stack
        // here before killing it: the threads stuck in binder or on a lock.
        sb.append("== watchdog stacks\n");
        appendStuckThreads(new File(root, "data/anr"), sb);
        sb.append("== kmsg (start)\n");
        int kept = 0;
        for (String l : LogTail.lines(new File(root, "dev/kmsg"), 4 << 20)) {
            if (l.contains("Unknown class") || l.contains("servicemanager: Caller")) continue;
            sb.append(l).append('\n');
            if (++kept >= 300) break;
        }
        sb.append("== /sys/fs/bpf\n");
        listPins(new File(root, "sys/fs/bpf"), "", sb, new int[] {0});
        sb.append("== log.txt (").append(problemsOnly ? "problems" : "end").append(")\n");
        // The same error repeated (a stack trace per retry): shown twice at most.
        Map<String, Integer> seen = new HashMap<>();
        for (String l : LogTail.lines(LogTail.file(this), bytes)) {
            if (problemsOnly && !LogTail.problem(l)) continue;
            // The Vulkan driver's buffer tracing: thousands of lines, never the problem.
            if (problemsOnly && l.contains("PANVKANB")) continue;
            String key = l.length() > 33 ? l.substring(33) : l;
            int n = seen.merge(key, 1, Integer::sum);
            if (problemsOnly && n > 2) continue;
            sb.append(l).append('\n');
        }
        // Shared storage last: a paste of the end of this screen carries it.
        sb.append("== vold.log (end)\n");
        for (String l : LogTail.lines(new File(root, "data/local/tmp/vold.log"), 8192)) {
            sb.append(l).append('\n');
        }
        // Shared storage: MediaProvider's FUSE thread, if it never answered
        // the stand-in's FUSE_INIT (saved by the vold stand-in), and the
        // storage lines of the log at every level.
        sb.append("== FUSE stacks\n");
        appendFuseThreads(new File(root, "data/local/tmp/fuse-stacks.txt"), sb);
        // libfuse reports why a FUSE session ends only on stderr, which
        // MediaProvider's isn't kept: the same library, run at start.
        sb.append("== fusetest\n");
        for (String l : LogTail.lines(new File(root, "data/local/tmp/fusetest.log"), 16384)) {
            sb.append(l).append('\n');
        }
        sb.append("== storage lines\n");
        List<String> st = new java.util.ArrayList<>();
        for (String l : LogTail.lines(LogTail.file(this), 8 << 20)) {
            if (l.contains("FuseDaemon") || l.contains("fuse") || l.contains("FUSE")
                    || l.contains("ExternalStorageService") || l.contains("StorageSession")) {
                if (!l.contains("\tat ")) st.add(l);
            }
        }
        for (String l : st.subList(Math.max(0, st.size() - 60), st.size())) sb.append(l).append('\n');
        // Last, so the end of a paste carries it: why apps stopped (crashes,
        // native aborts, ANRs, process deaths) and browser (Gecko) lines.
        sb.append("== app crashes\n");
        List<String> cr = new java.util.ArrayList<>();
        for (String l : LogTail.lines(LogTail.file(this), 16 << 20)) {
            if (l.contains("PANVKANB") || l.contains("Fence: merge")) continue;
            if (l.contains("AndroidRuntime") || l.contains("FATAL") || l.contains(" F DEBUG")
                    || l.contains(" F libc") || l.contains("has died") || l.contains("ANR in")
                    || l.contains("Gecko") || l.contains("mozilla") || l.contains("crash_dump")
                    || l.contains("Fatal signal") || l.contains("SIGSYS") || l.contains("seccomp")
                    || (l.length() > 31 && l.charAt(31) == 'F')) {
                cr.add(l);
            }
        }
        for (String l : cr.subList(Math.max(0, cr.size() - 200), cr.size())) sb.append(l).append('\n');
        return sb.toString();
    }

    // The newest stack dump in /data/anr: the threads blocked on a lock or
    // waiting in a binder call (the cause of a watchdog kill).
    private static void appendStuckThreads(File dir, StringBuilder sb) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            sb.append("(none)\n");
            return;
        }
        File newest = files[0];
        for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
        sb.append(newest.getName()).append('\n');
        StringBuilder block = new StringBuilder();
        int total = 0;
        for (String l : LogTail.lines(newest, 8 << 20)) {
            if (!l.isEmpty()) {
                block.append(l).append('\n');
                continue;
            }
            String b = block.toString();
            block.setLength(0);
            boolean stuck = b.contains("state=S") && (b.contains("IPCThreadState::transact")
                    || b.contains("BinderProxy.transact")) || b.contains("waiting to lock")
                    || b.contains("Blocked") || b.startsWith("\"main\"");
            if (!stuck || b.startsWith("----")) continue;
            if (total + b.length() > 60000) break;
            sb.append(b).append('\n');
            total += b.length();
        }
    }

    // The threads of a MediaProvider stack dump that serve FUSE.
    private static void appendFuseThreads(File f, StringBuilder sb) {
        if (!f.exists()) {
            sb.append("(none)\n");
            return;
        }
        StringBuilder block = new StringBuilder();
        int total = 0;
        for (String l : LogTail.lines(f, 4 << 20)) {
            if (!l.isEmpty()) {
                block.append(l).append('\n');
                continue;
            }
            String b = block.toString();
            block.setLength(0);
            if (!(b.contains("Fuse") || b.contains("fuse") || b.startsWith("\"main\""))) continue;
            if (total + b.length() > 30000) break;
            sb.append(b).append('\n');
            total += b.length();
        }
    }

    private static void listPins(File dir, String prefix, StringBuilder sb, int[] count) {
        File[] files = dir.listFiles();
        if (files == null) {
            sb.append(prefix.isEmpty() ? "(missing)\n" : "");
            return;
        }
        for (File f : files) {
            if (count[0]++ > 300) return;
            sb.append(prefix).append(f.getName()).append(f.isDirectory() ? "/" : "")
                    .append(f.isDirectory() ? "" : " " + f.length()).append('\n');
            if (f.isDirectory()) listPins(f, prefix + f.getName() + "/", sb, count);
        }
    }

    // Reading megabytes of log: off the UI thread (it froze the app).
    private void load() {
        text.setText("Loading…");
        boolean p = problemsOnly;
        new Thread(() -> {
            String log = collect(p ? 4 << 20 : 256 << 10);
            if (log.length() > 200_000) log = log.substring(log.length() - 200_000);
            String shown = log;
            runOnUiThread(() -> {
                text.setText(shown);
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
            });
        }, "log-load").start();
    }

    private void share() {
        boolean p = problemsOnly;
        new Thread(() -> {
            String log = collect(p ? 4 << 20 : 512 << 10);
            if (log.length() > 400_000) log = log.substring(log.length() - 400_000);
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Android 17 log")
                    .putExtra(Intent.EXTRA_TEXT, log);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, "Share the log")));
        }, "log-share").start();
    }
}
