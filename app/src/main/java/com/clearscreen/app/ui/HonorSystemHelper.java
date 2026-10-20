package com.clearscreen.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/**
 * 针对荣耀MagicOS（Magic6系列）与主流定制系统的自启与保活跳转适配
 */
public class HonorSystemHelper {

    /**
     * 跳转荣耀系统“应用启动管理”
     * 允许清屏自动管理切换为手动管理（允许自启动、允许关联启动、允许后台活动）
     */
    public static void openHonorAutoStartSettings(Context context) {
        try {
            Intent intent = new Intent();
            // 荣耀 / 华为统一自启动管理器
            ComponentName comp = new ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            );
            intent.setComponent(comp);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e1) {
            try {
                // 备用方案：荣耀安全管家 / 系统管家主页
                Intent intent = new Intent();
                ComponentName comp = new ComponentName(
                        "com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                );
                intent.setComponent(comp);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e2) {
                // 通用应用详情
                openAppDetailsSettings(context);
            }
        }
    }

    public static void openAppDetailsSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
