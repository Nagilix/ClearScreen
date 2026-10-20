package com.clearscreen.app.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.clearscreen.app.ClearScreenApp;
import com.clearscreen.app.R;
import com.clearscreen.app.ui.MainActivity;

public class KeepAliveForegroundService extends Service {

    private static final int NOTIFICATION_ID = 1001;

    public static void start(Context context) {
        Intent intent = new Intent(context, KeepAliveForegroundService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, ClearScreenApp.CHANNEL_ID)
                .setContentTitle("清屏防护运行中")
                .setContentText("正在实时跳过开屏广告与防误触拦截")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY; // 被杀后尝试自动重建
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
