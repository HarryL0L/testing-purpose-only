package io.github.harryl0l.a17;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.zip.ZipFile;

/**
 * Sets Android 17 up in the app, without Termux: downloads the Cuttlefish
 * image (resumably) and builds the root from it (RootBuilder) with a17-mini,
 * which ships in the APK. A foreground service, so the phone lets it run for
 * the half hour or more this takes; if the app is killed, it carries on when
 * the system restarts it (the download from where it was, the build anew).
 */
public final class SetupService extends Service {
    static final String BUILD = "16644040";
    static final String TARGET = "aosp_cf_arm64_only_phone";
    static final String URL = "https://ci.android.com/builds/submitted/" + BUILD + "/" + TARGET
            + "-userdebug/latest/raw/" + TARGET + "-img-" + BUILD + ".zip";
    // The image zip of that build (about 1.1 GB), for the space check only.
    private static final long ZIP_BYTES = 1_101_174_958L;
    // The root (about 3 GB) and the partition images while it's built (1.8 GB,
    // shrinking as they're unpacked), with a margin.
    private static final long BUILD_BYTES = 4_500L << 20;

    private static final String CHANNEL = "setup";
    private static final String ACTION_CANCEL = "io.github.harryl0l.a17.CANCEL_SETUP";
    private static final int NOTIFICATION = 2;

    // What MainActivity shows.
    static volatile boolean running;
    static volatile String status = "";
    static volatile String error;

    private static volatile boolean cancel;
    private long lastNotified;

    static File logFile(Context c) { return new File(c.getFilesDir(), "a17-setup.log"); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        android.content.SharedPreferences prefs = getSharedPreferences("setup", MODE_PRIVATE);
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancel = true;
            prefs.edit().putBoolean("wanted", false).commit();
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        // A restart by the system after the app was killed: only if a setup was under way.
        if (intent == null && !prefs.getBoolean("wanted", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs.edit().putBoolean("wanted", true).commit();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Android 17 setup", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification("Setting up Android 17", 0, 0));
        if (!running) {
            running = true;
            error = null;
            cancel = false;
            new Thread(this::work, "a17-setup").start();
        }
        return START_STICKY;
    }

    private Notification notification(String text, long done, long total) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 4,
                new Intent(this, SetupService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Setting up Android 17")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Cancel", stop).build())
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (total > 0) b.setProgress(1000, (int) (done * 1000 / total), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void show(String text, long done, long total, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotified < 1000) return;  // the system drops faster updates
        lastNotified = now;
        status = total > 0 ? String.format("%s: %.0f of %.0f MB", text, done / 1e6, total / 1e6) : text;
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(status, done, total));
    }

    private synchronized void log(String line) {
        try (FileWriter w = new FileWriter(logFile(this), true)) {
            w.write("setup: " + line + "\n");
        } catch (IOException e) {
            // the status line still has it
        }
    }

    private void work() {
        File files = getFilesDir();
        File zip = new File(files, "a17-image.zip");
        File mini = new File(files, "a17-mini");
        File build = new File(files, "a17-new");
        File a17 = MainActivity.Paths.a17(this);
        PowerManager.WakeLock wake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a17:setup");
        wake.acquire();
        boolean ok = false;
        try {
            log("started " + new Date() + ", image " + URL);
            if (MainActivity.Paths.installed(this)) {
                ok = true;
                return;
            }
            // Leftovers of an earlier try, and a root without run.sh (a
            // half-finished import from Termux): of no use, and in the way.
            show("Checking free storage", 0, 0, true);
            RootBuilder.deleteTree(build);
            RootBuilder.deleteTree(mini);
            RootBuilder.deleteTree(a17);
            long free = new StatFs(files.getPath()).getAvailableBytes();
            File part = new File(zip.getPath() + ".part");
            long toDownload = zip.exists() ? 0 : Math.max(0, ZIP_BYTES - (part.exists() ? part.length() : 0));
            if (free < BUILD_BYTES + toDownload) {
                throw new IOException(String.format("Not enough free storage: %.1f GB free, %.1f GB needed "
                        + "(Android 17 takes about 3 GB, plus about 1.5 GB while it's set up%s)",
                        free / 1e9, (BUILD_BYTES + toDownload) / 1e9,
                        toDownload > 0 ? ", plus the 1.1 GB download" : ""));
            }
            download(zip);
            if (cancel) throw new RootBuilder.Cancelled();

            show("Unpacking a17-mini", 0, 0, true);
            // assets/a17-mini.tar.gz: the build stores it ungzipped, as a17-mini.tar
            try (InputStream in = getAssets().open("a17-mini.tar")) {
                RootBuilder.untar(in, mini);
            }
            String logPath = logFile(this).getAbsolutePath();
            new RootBuilder(new RootBuilder.Host() {
                @Override public void log(String line) {
                    SetupService.this.log(line);
                    show(line, 0, 0, true);
                }
                @Override public int run(String dir, String[] argv) { return Native.run(dir, argv, logPath); }
                @Override public boolean cancelled() { return cancel; }
            }, mini).build(zip, build);

            // Only now does the app see Android 17 as installed (run.sh in a17).
            RootBuilder.rename(build, a17);
            ok = true;
            zip.delete();
            RootBuilder.deleteTree(mini);
            log("done " + new Date());
        } catch (Throwable e) {  // an Error (out of memory, ...) too: the cleanup below
            String msg = e instanceof RootBuilder.Cancelled ? "cancelled" : e.getMessage();
            log("failed: " + e);
            android.util.Log.e("a17", "setup", e);
            error = msg != null ? msg : e.toString();
            // the download stays for the next try; the rest is rebuilt
            try {
                RootBuilder.deleteTree(build);
                RootBuilder.deleteTree(mini);
            } catch (IOException x) {
                log("cleanup: " + x.getMessage());
            }
        } finally {
            getSharedPreferences("setup", MODE_PRIVATE).edit().putBoolean("wanted", false).commit();
            status = ok ? "Android 17 is set up" : "Setup failed: " + error;
            running = false;
            wake.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    // ------------------------------------------------- download (resumable)

    private void download(File zip) throws IOException {
        File part = new File(zip.getPath() + ".part");
        if (!zip.exists()) {
            for (int attempt = 0; ; attempt++) {
                try {
                    downloadOnce(part);
                    break;
                } catch (RootBuilder.Cancelled e) {
                    throw e;
                } catch (IOException e) {
                    log("download: " + e);
                    if (attempt >= 5) throw new IOException("Download failed: " + e.getMessage(), e);
                    show("Download interrupted, retrying…", 0, 0, true);
                    try {
                        Thread.sleep(3000L << attempt);
                    } catch (InterruptedException ie) {
                        throw new RootBuilder.Cancelled();
                    }
                }
            }
            RootBuilder.rename(part, zip);
        }
        // A damaged download would fail every build: checked once, dropped if bad.
        try (ZipFile z = new ZipFile(zip)) {
            if (z.getEntry("super.img") == null) throw new IOException("no super.img in it");
        } catch (IOException e) {
            zip.delete();
            throw new IOException("The download was damaged (" + e.getMessage() + "): try again", e);
        }
    }

    private void downloadOnce(File part) throws IOException {
        long have = part.exists() ? part.length() : 0;
        HttpURLConnection h = (HttpURLConnection) new URL(URL).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(30000);
        h.setReadTimeout(60000);
        if (have > 0) h.setRequestProperty("Range", "bytes=" + have + "-");
        int code = h.getResponseCode();
        long total;
        boolean append;
        if (code == 206) {
            append = true;
            total = have + h.getContentLengthLong();
        } else if (code == 200) {
            append = false;
            have = 0;
            total = h.getContentLengthLong();
        } else if (code == 416) {  // complete already
            h.disconnect();
            return;
        } else {
            h.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
            byte[] buf = new byte[1 << 16];
            long done = have;
            show("Downloading Android 17", done, total, true);
            for (int n; (n = in.read(buf)) > 0; ) {
                if (cancel) throw new RootBuilder.Cancelled();
                out.write(buf, 0, n);
                done += n;
                show("Downloading Android 17", done, total, false);
            }
            if (total > 0 && done < total) throw new IOException("connection closed early");
        } finally {
            h.disconnect();
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
