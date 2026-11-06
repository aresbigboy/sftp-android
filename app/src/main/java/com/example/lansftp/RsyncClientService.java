package com.example.lansftp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** rsync 客户端前台服务：传输期间显示常驻通知，防止后台/锁屏后系统限制网络 */
public class RsyncClientService extends Service {

    public static final String ACTION_START = "com.example.lansftp.rsync.START";
    public static final String ACTION_STOP = "com.example.lansftp.rsync.STOP";

    private static final String CHANNEL_ID = "rsync_client";
    private static final int NOTIF_ID = 2;

    /** 启动前台服务（rsync 传输开始时调用） */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, RsyncClientService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** 停止前台服务（rsync 传输结束时调用） */
    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, RsyncClientService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification("rsync 传输中", "正在同步文件，请保持 App 后台运行"));
        return START_STICKY;
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, RsyncClientActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_server)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "rsync 客户端", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
