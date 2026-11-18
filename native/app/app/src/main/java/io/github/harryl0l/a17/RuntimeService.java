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
        if (network == null) {
            // The phone's DNS servers for Android 17's name lookups (napexd's
            // nmode.NetService answers getaddrinfo with them)
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            network = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onLinkPropertiesChanged(android.net.Network n,
                        android.net.LinkProperties lp) { writeDns(a17, lp); }
            };
            cm.registerDefaultNetworkCallback(network);
        }
        if (!Native.running()) {
            String dir = a17;
            new Thread(() -> startAndroid(dir), "a17-start").start();
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
            // the composer HAL (it resends the last frame to a viewer that
            // reconnects, e.g. after the app was in the background)
            {"ncomposer", "root/system/bin/ncomposer"},
            // our bionic (one build: libc, libdl, libm and the linker together)
            {"libc.so", "root/apex/com.android.runtime/lib64/bionic/libc.so"},
            {"libdl.so", "root/apex/com.android.runtime/lib64/bionic/libdl.so"},
            {"libm.so", "root/apex/com.android.runtime/lib64/bionic/libm.so"},
            {"linker64", "root/apex/com.android.runtime/bin/linker64"},
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
     * FUSE): one set of files. Older roots made them two folders, so Files saw
     * one and MediaProvider (and imports) the other. The real folder is
     * /storage/emulated/0 (apps resolve paths and expect them there; the
     * storage provider refuses anything else) and /data/media/0 links to it.
     */
    private static void linkSharedStorage(String dir) {
        java.io.File media = new java.io.File(dir, "root/data/media/0");
        java.io.File user = new java.io.File(dir, "root/storage/emulated/0");
        java.nio.file.Path mp = media.toPath(), up = user.toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(mp)) return;  // done already
            if (java.nio.file.Files.isSymbolicLink(up)) {
                // v0.27 linked the other way round: swap
                java.nio.file.Files.delete(up);
                if (media.isDirectory() && !media.renameTo(user)) {
                    android.util.Log.e("a17", "can't move " + media);
                    return;
                }
            } else if (media.isDirectory()) {
                user.mkdirs();
                mergeInto(media, user);
                java.io.File rest = new java.io.File(user, ".old-data-media-0");
                if (!media.delete() && !media.renameTo(rest)) {
                    android.util.Log.e("a17", "can't replace " + media);
                    return;
                }
            }
            user.mkdirs();
            media.getParentFile().mkdirs();
            java.nio.file.Files.createSymbolicLink(mp, java.nio.file.Paths.get("../../storage/emulated/0"));
        } catch (Exception e) {
            android.util.Log.e("a17", "shared storage link", e);
        }
    }

    // Moves what `from` holds into `to`, one level of folders deep; what both
    // have stays in `from`.
    private static void mergeInto(java.io.File from, java.io.File to) {
        java.io.File[] items = from.listFiles();
        if (items == null) return;
        for (java.io.File item : items) {
            java.io.File t = new java.io.File(to, item.getName());
            if (!t.exists()) {
                item.renameTo(t);
            } else if (item.isDirectory() && t.isDirectory()) {
                java.io.File[] inner = item.listFiles();
                if (inner != null) {
                    for (java.io.File i : inner) {
                        java.io.File ti = new java.io.File(t, i.getName());
                        if (!ti.exists()) i.renameTo(ti);
                    }
                }
            }
        }
    }

    private BroadcastReceiver battery;

    private android.net.ConnectivityManager.NetworkCallback network;

    private static final String STUBS = "CLASSPATH=/system/framework/napexd.jar app_process64 /system/bin nmode.StubService";
    private static final String NET_SERVICE =
            "# fwmarkd and dnsproxyd: netd's sockets every app's connect() and\n"
            + "# getaddrinfo() use (nmode.NetService, napexd.jar)\n"
            + "CLASSPATH=/system/framework/napexd.jar app_process64 /system/bin nmode.NetService \\\n"
            + "    </dev/null >/dev/null 2>&1 &\n";

    // media.extractor and media.swcodec (their seccomp filters are skipped by
    // libc: they allow only the binder driver's system calls)
    private static final String EXTRACTOR =
            "mediaextractor </dev/null >/dev/null 2>&1 &\n"
            + "/apex/com.android.media.swcodec/bin/mediaswcodec </dev/null >/dev/null 2>&1 &\n";

    private static final String MEDIA_SERVICES =
            "# media.player, media.metrics, media.camera\n"
            + EXTRACTOR
            + "mediaserver </dev/null >/dev/null 2>&1 &\n"
            + "mediametrics </dev/null >/dev/null 2>&1 &\n"
            + "cameraserver </dev/null >/dev/null 2>&1 &\n";

    /**
     * The phone's battery for Android 17's battery HAL (napexd's nmode.Health),
     * in BatteryManager's own numbers.
     */
    private static void writeDns(String dir, android.net.LinkProperties lp) {
        StringBuilder sb = new StringBuilder();
        for (java.net.InetAddress a : lp.getDnsServers()) sb.append(a.getHostAddress()).append('\n');
        java.io.File f = new java.io.File(dir, "root/data/local/tmp/ndns");
        java.io.File tmp = new java.io.File(dir, "root/data/local/tmp/ndns.new");
        try {
            java.nio.file.Files.write(tmp.toPath(), sb.toString().getBytes());
            tmp.renameTo(f);
        } catch (java.io.IOException e) {
            android.util.Log.e("a17", "can't write DNS servers", e);
        }
    }

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
            if (!t.contains("mediaextractor </dev/null"))
                t = t.replace("mediaserver </dev/null", EXTRACTOR + "mediaserver </dev/null");
            if (!t.contains("nmode.NetService")) t = t.replace(STUBS, NET_SERVICE + STUBS);
            if (t.equals(s)) return;
            java.nio.file.Files.write(f.toPath(), t.getBytes());
        } catch (java.io.IOException e) {
            android.util.Log.e("a17", "can't update nzygote", e);
        }
    }

    static final String ACTION_POWER_OFF = "io.github.harryl0l.a17.POWER_OFF";
    private volatile boolean watching;

    private void startAndroid(String dir) {
        installUpdates(dir);
        patchNzygote(dir);
        linkSharedStorage(dir);
        repairAppDataOwners(dir);
        new java.io.File(dir, POWERCTL).delete();
        java.util.List<String> props = MainActivity.Paths.screenProps(this);
        props.add("ro.input.video_enabled=false");
        // the phone's time zone (the clock is the phone's already)
        props.add("persist.sys.timezone=" + java.util.TimeZone.getDefault().getID());
        // Zygote hides other apps' data by mounting a tmpfs over /data/data
        // and recreating the app's own directory, as root, in it. Mounts do
        // nothing in an app, so that marked every started app's real data
        // directory root-owned; at the next boot installd refused it and
        // PackageManager "recovered" by wiping the app's data.
        props.add("persist.zygote.app_data_isolation=false");
        props.add("persist.zygote.vold_app_data_isolation=false");
        Native.start(dir, props.toArray(new String[0]));
        if (!watching) {
            watching = true;
            new Thread(() -> watchPower(dir), "a17-power").start();
        }
    }

    // Android 17's power menu: "Power off" and "Restart" end in sys.powerctl,
    // which nprops writes to this file (init would act on it).
    private static final String POWERCTL = "root/data/local/tmp/npowerctl";

    private void watchPower(String dir) {
        java.io.File f = new java.io.File(dir, POWERCTL);
        while (watching) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            if (!f.exists()) continue;
            String v;
            try {
                v = new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
            } catch (java.io.IOException e) {
                v = "";
            }
            f.delete();
            android.util.Log.i("a17", "sys.powerctl=" + v);
            Native.stop(dir);
            if (v.startsWith("reboot")) {
                startAndroid(dir);
            } else {
                watching = false;
                sendBroadcast(new Intent(ACTION_POWER_OFF).setPackage(getPackageName()));
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
    }

    /**
     * App data directories marked root-owned by zygote's data isolation (see
     * startAndroid) get their app's owner back, from their cache directory
     * (which keeps it), before installd checks them.
     */
    private static void repairAppDataOwners(String dir) {
        for (String base : new String[] {"root/data/data", "root/data/user_de/0"}) {
            java.io.File[] pkgs = new java.io.File(dir, base).listFiles();
            if (pkgs == null) continue;
            for (java.io.File p : pkgs) {
                try {
                    int[] own = owner(p.getPath());
                    if (own == null || own[0] != 0 || own[1] != 0) continue;
                    int[] cache = owner(p.getPath() + "/cache");
                    if (cache == null || cache[0] % 100000 < 10000) continue;
                    java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(cache[0]).putInt(cache[0]);
                    android.system.Os.setxattr(p.getPath(), OWNER_XATTR, b.array(), 0);
                } catch (Exception e) {
                    // a symlink, or no record: leave it
                }
            }
        }
    }

    // native mode's libc keeps file owners here (uint32 uid, gid)
    private static final String OWNER_XATTR = "user.nmode.owner";

    private static int[] owner(String path) throws android.system.ErrnoException {
        byte[] v = android.system.Os.getxattr(path, OWNER_XATTR);
        if (v.length != 8) return null;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(v).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        return new int[] {b.getInt(), b.getInt()};
    }

    @Override
    public void onDestroy() {
        watching = false;
        if (battery != null) unregisterReceiver(battery);
        if (network != null) {
            getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(network);
            network = null;
        }
        Native.stop(MainActivity.Paths.a17(this).getAbsolutePath());
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
