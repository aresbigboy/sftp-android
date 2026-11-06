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

/** 前台服务：让 SFTP 服务器在后台持续运行，并显示常驻通知 */
public class SftpServerService extends Service {

    public static final String ACTION_START = "com.example.lansftp.action.START";
    public static final String ACTION_STOP = "com.example.lansftp.action.STOP";
    public static final String STATUS_BROADCAST = "com.example.lansftp.STATUS";

    private static final String CHANNEL_ID = "sftp_server";
    private static final int NOTIF_ID = 1;

    /** 保存配置并启动前台服务 */
    public static void start(Context ctx, int port, String username, String password, String rootPath) {
        Settings s = new Settings();
        s.port = port;
        s.username = username;
        s.password = password;
        s.rootPath = rootPath;
        s.save(ctx);
        Intent i = new Intent(ctx, SftpServerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** 请求停止服务（onDestroy 中会停止服务器） */
    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, SftpServerService.class).setAction(ACTION_STOP));
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

        // 立即进入前台（满足 startForegroundService 5 秒内必须 startForeground 的要求），
        // 再在后台线程启动服务器，避免主线程阻塞/ANR
        startForeground(NOTIF_ID, buildNotification("SFTP 服务器启动中", "正在初始化…"));
        // 不再常驻 WakeLock（省电，Termux 式）：空闲时手机可深度休眠，
        // 有 SSH 连接进来时由 SftpServerManager 按需持锁，全部断开后释放。

        new Thread(() -> {
            Settings s = Settings.load(this);
            boolean ok = SftpServerManager.getInstance(this)
                    .start(s.port, s.username, s.password, s.rootPath, s.authorizedKeys);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> onServerStartResult(ok));
        }, "sftp-starter").start();

        return START_STICKY;
    }

    private void onServerStartResult(boolean ok) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (ok) {
            Settings s = Settings.load(this);
            String text = NetworkUtils.buildConnectionAddressText(s.username, s.port)
                    + "  根目录: " + s.rootPath;
            nm.notify(NOTIF_ID, buildNotification("SFTP 服务器运行中", text));
            sendStatusBroadcast(true);
        } else {
            String err = SftpServerManager.getInstance(this).getLastError();
            nm.notify(NOTIF_ID, buildNotification("SFTP 启动失败", err == null ? "未知错误" : err));
            sendStatusBroadcast(false);
            stopSelf();
        }
    }

    private Notification buildNotification(String title, String text) {        Intent open = new Intent(this, MainActivity.class);
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
                    CHANNEL_ID, "SFTP 服务器", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void sendStatusBroadcast(boolean running) {
        Intent i = new Intent(STATUS_BROADCAST);
        i.setPackage(getPackageName());
        i.putExtra("running", running);
        sendBroadcast(i);
    }

    @Override
    public void onDestroy() {
        SftpServerManager.getInstance(this).stop();
        sendStatusBroadcast(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
