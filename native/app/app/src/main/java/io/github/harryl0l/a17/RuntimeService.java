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
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        startForeground(1, n);
        if (!Native.running()) {
            String dir = MainActivity.Paths.a17(this).getAbsolutePath();
            new Thread(() -> Native.start(dir, new String[] {"ro.input.video_enabled=false"}),
                    "a17-start").start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Native.stop(MainActivity.Paths.a17(this).getAbsolutePath());
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
