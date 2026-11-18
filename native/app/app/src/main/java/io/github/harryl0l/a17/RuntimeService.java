package io.github.harryl0l.a17;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        startForeground(1, n);
        if (!Native.running()) {
            String dir = MainActivity.Paths.a17(this).getAbsolutePath();
            new Thread(() -> {
                installUpdates(dir);
                Native.start(dir, new String[] {"ro.input.video_enabled=false"});
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

    @Override
    public void onDestroy() {
        Native.stop(MainActivity.Paths.a17(this).getAbsolutePath());
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
