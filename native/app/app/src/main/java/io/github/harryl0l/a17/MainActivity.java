package io.github.harryl0l.a17;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.util.List;

/**
 * Android 17's screen, full screen, with touch and keys going back to it.
 * Without an installed root it offers to set one up (SetupService) or to
 * import one from Termux.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface;
    private TextView status;
    private Button logButton;
    private Button installButton;
    private String importStatus;  // the Termux import's progress, once one has begun
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The whole screen, the notch's strip included (black: the notch is there).
        android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(wlp);
        // Content under the (hidden) bars too, so its size doesn't depend on them.
        getWindow().setDecorFitsSystemWindows(false);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(48, 96, 48, 48);
        status.setTextSize(11);
        // Android 17's display is the screen below the notch, at full size.
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        root.addView(status);
        logButton = new Button(this);
        logButton.setText("Log");
        logButton.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, 48, 96);
        root.addView(logButton, lp);
        installButton = new Button(this);
        installButton.setText("Install Android 17");
        installButton.setVisibility(View.GONE);
        installButton.setOnClickListener(v -> {
            installButton.setVisibility(View.GONE);
            startForegroundService(new Intent(this, SetupService.class));
        });
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        ip.setMargins(48, 0, 0, 96);
        root.addView(installButton, ip);
        setContentView(root);
        surface.getHolder().addCallback(this);
        hideSystemBars();
        // Measured once the window is laid out: its real size and where the
        // notch is in it (phones differ in what they allow apps).
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l <= 0 || b - t <= 0) return;
                root.removeOnLayoutChangeListener(this);
                // The whole window is Android 17's display; a camera cutout at
                // the top goes to it as its own (CutoutOverlay), so its status bar
                // keeps clear of the hole as on a phone.
                android.graphics.Rect hole = null;
                WindowInsets in = root.getRootWindowInsets();
                if (in != null && in.getDisplayCutout() != null) {
                    hole = in.getDisplayCutout().getBoundingRectTop();
                }
                // The phone's status bar (hidden here, so regardless of visibility).
                // Its inset, and the height the phone's own status bar uses (which
                // some, realme's, make taller than the inset it reports).
                int inset = in != null
                        ? in.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top : 0;
                int res = getResources().getIdentifier("status_bar_height", "dimen", "android");
                int own = res != 0 ? getResources().getDimensionPixelSize(res) : 0;
                final int statusBar = Math.max(inset, own);
                final String screenInfo = "window " + (r - l) + "x" + (b - t) + ", cutout " + hole
                        + ", status bar inset " + inset + ", status_bar_height " + own;
                Paths.saveScreen(MainActivity.this, r - l, b - t);
                final android.graphics.Rect cutout = hole;
                File a17 = Paths.a17(MainActivity.this);
                new Thread(() -> {
                    if (Paths.installed(MainActivity.this)) {
                        CutoutOverlay.write(MainActivity.this, a17, cutout, statusBar);
                        try (java.io.FileWriter w = new java.io.FileWriter(new File(a17, "screen.txt"))) {
                            w.write(screenInfo + "\n");
                        } catch (java.io.IOException e) {
                            // only for the log screen
                        }
                    }
                    ui.post(MainActivity.this::begin);
                }, "a17-cutout").start();
            }
        });
    }

    private void begin() {
        if (!Paths.installed(this) || SetupService.running) {
            Importer.start(this, msg -> ui.post(() -> importStatus = msg));
            checkSetup();
        } else {
            startRuntime();
        }
    }

    // Until Android 17 is installed: the setup's progress, or how to get it.
    private void checkSetup() {
        boolean setup = SetupService.running;
        if (!setup && Paths.installed(this)) {
            installButton.setVisibility(View.GONE);
            startRuntime();
            return;
        }
        // An import from Termux under way rules out a setup (a failed one doesn't).
        boolean importing = importStatus != null && importStatus.startsWith("Import")
                && !importStatus.startsWith("Import failed");
        installButton.setVisibility(setup || importing ? View.GONE : View.VISIBLE);
        String err = SetupService.error;
        if (setup) {
            status.setText("Setting up Android 17…\n\n" + SetupService.status + "\n\nThis takes half an "
                    + "hour or more. The app may be left meanwhile: the setup carries on (see its "
                    + "notification), and Android 17 starts when it's done.");
        } else if (err != null) {
            status.setText("Setup failed: " + err + "\n\n" + Importer.INSTRUCTIONS);
        } else if (importStatus != null) {
            status.setText(importStatus);
        } else {
            status.setText(Importer.INSTRUCTIONS);
        }
        ui.postDelayed(this::checkSetup, 1000);
    }

    private void startRuntime() {
        status.setText("Starting Android 17…");
        startForegroundService(new Intent(this, RuntimeService.class));
        ui.postDelayed(this::checkConnected, 1000);
    }

    // Until Android 17's screen arrives: the end of its log, live.
    private void checkConnected() {
        if (Native.displaySize() != null) {
            status.setText("");
            status.setVisibility(View.GONE);
            logButton.setVisibility(View.GONE);
            return;
        }
        status.setVisibility(View.VISIBLE);
        logButton.setVisibility(View.VISIBLE);
        StringBuilder sb = new StringBuilder("Starting Android 17…\n\n");
        List<String> lines = LogTail.lines(LogTail.file(this), 16 << 10);
        lines.removeIf(l -> l.contains("Fence: merge"));
        for (int i = Math.max(0, lines.size() - 14); i < lines.size(); i++) {
            String l = lines.get(i);
            sb.append(l.length() > 120 ? l.substring(0, 120) : l).append('\n');
        }
        status.setText(sb);
        ui.postDelayed(this::checkConnected, 1000);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { Native.viewerStart(h.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { Native.viewerStop(); }

    // The frame is drawn scaled to fit the view, centred: map a touch back.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int[] d = Native.displaySize();
        if (d == null) return false;
        float vw = surface.getWidth(), vh = surface.getHeight();
        float scale = Math.min(vw / d[0], vh / d[1]);
        float ox = (vw - d[0] * scale) / 2, oy = (vh - d[1] * scale) / 2;
        float ex = e.getX() - surface.getLeft(), ey = e.getY() - surface.getTop();
        int x = Math.round((ex - ox) / scale), y = Math.round((ey - oy) / scale);
        int kind;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: kind = 0; break;
            case MotionEvent.ACTION_UP: kind = 1; break;
            case MotionEvent.ACTION_MOVE: kind = 2; break;
            case MotionEvent.ACTION_CANCEL: kind = 3; break;
            default: return true;  // a second finger: one pointer for now
        }
        Native.input(kind, x, y);
        return true;
    }

    // Back, volume and the rest go to Android 17 as its own keys.
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (Native.displaySize() == null) return super.onKeyDown(code, e);
        Native.input(4, code, 1);
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (Native.displaySize() == null) return super.onKeyUp(code, e);
        Native.input(4, code, 0);
        return true;
    }

    static final class Paths {
        static File a17(android.content.Context c) { return new File(c.getFilesDir(), "a17"); }
        static boolean installed(android.content.Context c) { return new File(a17(c), "run.sh").exists(); }

        /**
         * Android 17's display: the window's area clear of the notch, at the
         * phone's density. Kept for RuntimeService, which starts Android 17 with it.
         */
        static void saveScreen(Activity a, int width, int height) {
            int dpi = a.getResources().getDisplayMetrics().densityDpi;
            a.getSharedPreferences("screen", MODE_PRIVATE).edit()
                    .putInt("w", width & ~1).putInt("h", height & ~1).putInt("dpi", dpi).commit();
        }

        /** Android 17's display properties from saveScreen, or none (its defaults). */
        static List<String> screenProps(android.content.Context c) {
            android.content.SharedPreferences p = c.getSharedPreferences("screen", MODE_PRIVATE);
            List<String> props = new java.util.ArrayList<>();
            if (p.getInt("w", 0) > 0) {
                props.add("native.display.width=" + p.getInt("w", 0));
                props.add("native.display.height=" + p.getInt("h", 0));
                props.add("native.display.dpi=" + p.getInt("dpi", 0));
            }
            return props;
        }
    }
}
