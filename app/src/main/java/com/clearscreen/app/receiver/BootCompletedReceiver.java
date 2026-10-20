package com.clearscreen.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.clearscreen.app.service.KeepAliveForegroundService;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "收到广播: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            // 开机自动启动前台常驻服务
            try {
                KeepAliveForegroundService.start(context);
                Log.d(TAG, "开机拉起 KeepAliveForegroundService 成功");
            } catch (Exception e) {
                Log.e(TAG, "拉起服务失败: ", e);
            }
        }
    }
}
