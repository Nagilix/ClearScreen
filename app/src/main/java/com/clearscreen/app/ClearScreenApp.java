package com.clearscreen.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class ClearScreenApp extends Application {
    public static final String CHANNEL_ID = "clearscreen_foreground_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "清屏常驻与拦截通知",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保证清屏后台拦截服务稳定运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
