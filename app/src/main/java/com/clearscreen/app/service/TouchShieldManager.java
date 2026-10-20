package com.clearscreen.app.service;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 防误触悬浮保护盾：
 * 在监测到App启动的开屏前2秒，在容易产生摇一摇误触/误点的大幅非跳过区域提供安全保护
 */
public class TouchShieldManager {

    private static TouchShieldManager instance;
    private final Context context;
    private final WindowManager windowManager;
    private View shieldView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isShowing = false;

    public static synchronized TouchShieldManager getInstance(Context context) {
        if (instance == null) {
            instance = new TouchShieldManager(context.getApplicationContext());
        }
        return instance;
    }

    private TouchShieldManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public synchronized void showTemporaryShield(long durationMs) {
        if (!Settings.canDrawOverlays(context)) {
            return;
        }

        mainHandler.post(() -> {
            if (isShowing && shieldView != null) {
                mainHandler.removeCallbacksAndMessages(null);
                scheduleDismiss(durationMs);
                return;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.CENTER;

            shieldView = new View(context);
            // 透明保护层拦截非跳过区的底层误触发
            shieldView.setBackgroundColor(0x01000000); 

            try {
                windowManager.addView(shieldView, params);
                isShowing = true;
                scheduleDismiss(durationMs);
            } catch (Exception ignored) {
            }
        });
    }

    private void scheduleDismiss(long delayMs) {
        mainHandler.postDelayed(() -> {
            synchronized (TouchShieldManager.this) {
                if (isShowing && shieldView != null) {
                    try {
                        windowManager.removeView(shieldView);
                    } catch (Exception ignored) {
                    }
                    shieldView = null;
                    isShowing = false;
                }
            }
        }, delayMs);
    }
}
