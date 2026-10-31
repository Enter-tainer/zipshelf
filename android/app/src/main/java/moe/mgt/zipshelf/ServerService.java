package moe.mgt.zipshelf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：离开 App 去 EinkBro 阅读时，HTTP 服务必须活着。
 *
 * 用 dataSync 类型。注意 Android 15+ 对 targetSdk≥35 的该类服务有 6 小时/24 小时上限，
 * 所以本 App 目前 targetSdk 34（侧载自用，不上架）。
 */
public final class ServerService extends Service {

    private static final String CHANNEL_ID = "server";
    private static final int NOTIFICATION_ID = 1001;
    private static final int REQ_STOP = 2;

    public static final String ACTION_STOP = "moe.mgt.zipshelf.action.STOP";

    public static void start(Context context) {
        Intent intent = new Intent(context, ServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            ServerCore.stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundCompat(buildNotification("正在启动…"));
        try {
            // 服务可能被系统单独重启（进程都可能是新的）：自己从磁盘恢复书单，
            // 不依赖 Activity 是否还活着。
            if (ServerCore.books().isEmpty()) {
                moe.mgt.zipshelf.data.BookStore store = new moe.mgt.zipshelf.data.BookStore(getFilesDir());
                ServerCore.setBooks(store.list());
            }
            int port = ServerCore.ensureStarted();
            int count = ServerCore.books().size();
            updateNotification("正在提供 " + count + " 本书 · 端口 " + port);
        } catch (Exception e) {
            updateNotification("服务启动失败：" + e.getMessage());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ServerCore.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "本地服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("为浏览器提供本地网页书服务");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, REQ_STOP, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("网页书服务")
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(
                                this, android.R.drawable.ic_menu_close_clear_cancel),
                        "停止", stopPi).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        return b.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
