package net.benfen.localproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 前台服务：托住代理服务器。安卓只冻结普通后台进程，
 * 前台服务（带常驻通知）是系统认可的常驻方式，这正是本应用存在的理由。
 */
public class ProxyService extends Service {
    public static final String ACTION_START = "net.benfen.localproxy.START";
    public static final String ACTION_STOP = "net.benfen.localproxy.STOP";
    private static final String CHANNEL = "tvproxy";
    private static final int NOTIF_ID = 8899;

    private static volatile ProxyServer server;
    private static volatile boolean running = false;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "本地代理", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("本地代理运行状态");
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopProxy();
            stopSelf();
            return START_NOT_STICKY;
        }
        startProxy();
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            try { startForeground(NOTIF_ID, n); } catch (Exception ignored) {}
        }
        return START_STICKY;
    }

    private synchronized void startProxy() {
        if (running) return;
        try {
            if (server == null) server = new ProxyServer();
            server.start();
            running = true;
            /* 持锁防 CPU 睡眠把连接拖死；配合电池豁免一起挡系统的后台冻结 */
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localproxy:proxy");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception e) {
            running = false;
        }
    }

    private synchronized void stopProxy() {
        running = false;
        if (server != null) server.stop();
        if (wakeLock != null) {
            try { wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ProxyService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
         .setContentTitle("本地代理运行中")
         .setContentText("127.0.0.1:8899 · 回电视机打开「本地代理」开关即可看")
         .setContentIntent(content)
         .setOngoing(true)
         .addAction(0, "停止", stopPi);
        return b.build();
    }

    @Override
    public void onDestroy() {
        stopProxy();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
