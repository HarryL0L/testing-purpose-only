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
        sb.append("== kmsg (end)\n");
        for (String l : LogTail.lines(new File(root, "dev/kmsg"), 8192)) sb.append(l).append('\n');
        sb.append("== /sys/fs/bpf\n");
        listPins(new File(root, "sys/fs/bpf"), "", sb, new int[] {0});
        sb.append("== log.txt (").append(problemsOnly ? "problems" : "end").append(")\n");
        // The same error repeated (a stack trace per retry): shown twice at most.
        Map<String, Integer> seen = new HashMap<>();
        for (String l : LogTail.lines(LogTail.file(this), bytes)) {
            if (problemsOnly && !LogTail.problem(l)) continue;
            String key = l.length() > 33 ? l.substring(33) : l;
            int n = seen.merge(key, 1, Integer::sum);
            if (problemsOnly && n > 2) continue;
            sb.append(l).append('\n');
        }
        return sb.toString();
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
