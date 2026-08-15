package com.dshharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：承载部署脚本服务（127.0.0.1:8045）。
 * 前台服务不受后台冻结影响，保证用户切到 Termux 粘贴命令时脚本始终可获取。
 */
public class DeployServerService extends Service {

    private static final String CHANNEL = "dsh_deploy";

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification());
        new Thread(new Runnable() {
            @Override public void run() {
                DeployServer.run(DeployAssets.BOOTSTRAP);
            }
        }).start();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "部署服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("供 Termux 获取部署脚本的本地服务");
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download);
        b.setContentTitle("DeepSeek Harness");
        b.setContentText("部署脚本服务运行中（127.0.0.1:8045）");
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
