package io.github.harryl0l.a17;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;

/**
 * Keeps Android 17's processes running (a foreground service, so the phone
 * doesn't stop the app in the background) and stops them with the app.
 */
public final class RuntimeService extends Service {
    private static final String CHANNEL = "runtime";
    private static final String ACTION_STOP = "io.github.harryl0l.a17.STOP";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Android 17", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RuntimeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Android 17 is running")
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Log",
                        PendingIntent.getActivity(this, 2, new Intent(this, LogActivity.class),
                                PendingIntent.FLAG_IMMUTABLE)).build())
                .addAction(new Notification.Action.Builder(null, "Import file",
                        PendingIntent.getActivity(this, 3, new Intent(this, ImportActivity.class),
                                PendingIntent.FLAG_IMMUTABLE)).build())
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        startForeground(1, n);
        String a17 = MainActivity.Paths.a17(this).getAbsolutePath();
        if (battery == null) {
            battery = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { writeBattery(a17, i); }
            };
            Intent sticky = registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) writeBattery(a17, sticky);
        }
        if (!Native.running()) {
            String dir = a17;
            new Thread(() -> {
                installUpdates(dir);
                patchNzygote(dir);
                linkSharedStorage(dir);
                java.util.List<String> props = MainActivity.Paths.screenProps(this);
                props.add("ro.input.video_enabled=false");
                // the phone's time zone (the clock is the phone's already)
                props.add("persist.sys.timezone=" + java.util.TimeZone.getDefault().getID());
                Native.start(dir, props.toArray(new String[0]));
            },
                    "a17-start").start();
        }
        return START_STICKY;
    }

    /**
     * Programs of native mode that ship with the app, newer than the imported
     * root's: copied over the root's copy before each start.
     */
    private void installUpdates(String dir) {
        String[][] files = {
            {"nbinder", "root/system/bin/nbinder"},
            // vold's stand-in (shared storage) and the codec store's (napexd)
            {"napexd.jar", "root/system/framework/napexd.jar"},
            // A one-off check at start of MediaProvider's FUSE library on this
            // kernel (tools/fusetest); libfuse.so is MediaProvider's own.
            {"fusetest", "root/data/local/tmp/fusetest/fusetest"},
            {"libfuse.so", "root/data/local/tmp/fusetest/libfuse.so"},
        };
        for (String[] f : files) {
            java.io.File dst = new java.io.File(dir, f[1]);
            java.io.File tmp = new java.io.File(dir, f[1] + ".new");
            dst.getParentFile().mkdirs();
            try (java.io.InputStream in = getAssets().open(f[0]);
                 java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (java.io.IOException e) {
                android.util.Log.e("a17", "can't install " + f[0], e);
                tmp.delete();
                continue;
            }
            tmp.setExecutable(true, false);
            if (!tmp.renameTo(dst)) android.util.Log.e("a17", "can't replace " + dst);
        }
    }

    /**
     * On a phone /storage/emulated/0 is /data/media/0 (through MediaProvider's
     * FUSE): one set of files. Older roots made it a folder of its own, so
     * Files saw one and MediaProvider (and imports) the other. Move what the
     * folder holds into /data/media/0 and make it a link there.
     */
    private static void linkSharedStorage(String dir) {
        java.io.File media = new java.io.File(dir, "root/data/media/0");
        java.io.File user = new java.io.File(dir, "root/storage/emulated/0");
        try {
            java.nio.file.Path up = user.toPath();
            if (java.nio.file.Files.isSymbolicLink(up)) return;
            media.mkdirs();
            if (user.isDirectory()) {
                java.io.File[] items = user.listFiles();
                if (items != null) {
                    for (java.io.File item : items) {
                        java.io.File to = new java.io.File(media, item.getName());
                        if (!to.exists()) {
                            item.renameTo(to);
                        } else if (item.isDirectory() && to.isDirectory()) {
                            java.io.File[] inner = item.listFiles();
                            if (inner != null) {
                                for (java.io.File i : inner) {
                                    java.io.File t = new java.io.File(to, i.getName());
                                    if (!t.exists()) i.renameTo(t);
                                }
                            }
                        }
                    }
                }
                // what both had stays, under a name of its own
                java.io.File rest = new java.io.File(media, ".old-storage-emulated-0");
                if (!user.delete() && !user.renameTo(rest)) {
                    android.util.Log.e("a17", "can't replace " + user);
                    return;
                }
            }
            user.getParentFile().mkdirs();
            java.nio.file.Files.createSymbolicLink(up, java.nio.file.Paths.get("../../data/media/0"));
        } catch (Exception e) {
            android.util.Log.e("a17", "shared storage link", e);
        }
    }

    private BroadcastReceiver battery;

    private static final String MEDIA_SERVICES =
            "# media.player, media.metrics, media.camera (mediaextractor's seccomp\n"
            + "# filter has no room for our binder, like mediaswcodec's)\n"
            + "mediaserver </dev/null >/dev/null 2>&1 &\n"
            + "mediametrics </dev/null >/dev/null 2>&1 &\n"
            + "cameraserver </dev/null >/dev/null 2>&1 &\n";

    /**
     * The phone's battery for Android 17's battery HAL (napexd's nmode.Health),
     * in BatteryManager's own numbers.
     */
    private void writeBattery(String dir, Intent i) {
        BatteryManager bm = getSystemService(BatteryManager.class);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 50);
        String s = "level=" + (scale > 0 ? level * 100 / scale : level)
                + "\nstatus=" + i.getIntExtra(BatteryManager.EXTRA_STATUS, 1)
                + "\nhealth=" + i.getIntExtra(BatteryManager.EXTRA_HEALTH, 2)
                + "\nplugged=" + i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                + "\npresent=" + (i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) ? 1 : 0)
                + "\nvoltage=" + i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3800)
                + "\ntemperature=" + i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250)
                + "\ncurrent_now=" + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                + "\ncurrent_average=" + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                + "\ncharge_counter=" + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                + "\n";
        java.io.File dst = new java.io.File(dir, "root/data/local/tmp/nbattery");
        java.io.File tmp = new java.io.File(dir, "root/data/local/tmp/nbattery.new");
        if (!dst.getParentFile().isDirectory()) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(s.getBytes());
        } catch (java.io.IOException e) {
            return;
        }
        tmp.setReadable(true, false);
        tmp.renameTo(dst);
    }

    /**
     * Older roots' nzygote starts the image's battery HAL (cuttlefish's, a fixed
     * 85%, always charging); napexd serves the phone's battery in its place.
     */
    private static void patchNzygote(String dir) {
        java.io.File f = new java.io.File(dir, "root/system/bin/nzygote");
        String line = "    /apex/com.google.cf.health/bin/hw/android.hardware.health-service.cuttlefish \\\n";
        // mediaserver (media.player: MediaProvider's scanner waits for it
        // forever), its metrics, and the camera service (no cameras yet).
        String audio = "audioserver </dev/null";
        String media = MEDIA_SERVICES + audio;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            String t = s.replace(line, "");
            if (!t.contains("mediaserver </dev/null")) t = t.replace(audio, media);
            if (t.equals(s)) return;
            java.nio.file.Files.write(f.toPath(), t.getBytes());
        } catch (java.io.IOException e) {
            android.util.Log.e("a17", "can't update nzygote", e);
        }
    }

    @Override
    public void onDestroy() {
        if (battery != null) unregisterReceiver(battery);
        Native.stop(MainActivity.Paths.a17(this).getAbsolutePath());
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
