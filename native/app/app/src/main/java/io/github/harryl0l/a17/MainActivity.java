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
 * Without an installed root it shows how to import one from Termux.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private SurfaceView surface;
    private TextView status;
    private Button logButton;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The whole screen, the notch's strip included (black: the notch is there).
        android.view.WindowManager.LayoutParams wlp = getWindow().getAttributes();
        wlp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(wlp);
        int[] screen = Paths.saveScreen(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(48, 96, 48, 48);
        status.setTextSize(11);
        // Android 17's display is the screen below the notch, at full size.
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -1);
        slp.topMargin = screen[3];
        slp.bottomMargin = screen[4];
        root.addView(surface, slp);
        root.addView(status);
        logButton = new Button(this);
        logButton.setText("Log");
        logButton.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, 48, 96);
        root.addView(logButton, lp);
        setContentView(root);
        surface.getHolder().addCallback(this);
        hideSystemBars();
        if (!Paths.installed(this)) {
            status.setText(Importer.INSTRUCTIONS);
            Importer.start(this, msg -> ui.post(() -> {
                status.setText(msg);
                if (Paths.installed(this)) startRuntime();
            }));
        } else {
            startRuntime();
        }
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
         * The screen for Android 17's display: {width, height, dpi, top, bottom},
         * the strips the notch (display cutout) takes at the top and bottom left
         * out. Kept for RuntimeService, which starts Android 17 with it.
         */
        static int[] saveScreen(Activity a) {
            android.view.WindowMetrics m = a.getWindowManager().getMaximumWindowMetrics();
            android.graphics.Rect b = m.getBounds();
            android.graphics.Insets cut =
                    m.getWindowInsets().getInsets(WindowInsets.Type.displayCutout());
            int w = b.width() & ~1, h = (b.height() - cut.top - cut.bottom) & ~1;
            int dpi = a.getResources().getDisplayMetrics().densityDpi;
            a.getSharedPreferences("screen", MODE_PRIVATE).edit()
                    .putInt("w", w).putInt("h", h).putInt("dpi", dpi).apply();
            return new int[] {w, h, dpi, cut.top, cut.bottom};
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
