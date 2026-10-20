package com.clearscreen.app.service;

import android.accessibilityservice.AccessibilityService;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clearscreen.app.data.AppPreferences;
import com.clearscreen.app.data.InterceptDatabaseHelper;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class ClearScreenAccessibilityService extends AccessibilityService {

    private static final String TAG = "ClearScreenService";

    // 匹配常规开屏广告跳过按钮关键词及正则表达式
    private static final List<String> SKIP_KEYWORDS = Arrays.asList(
            "跳过", "skip", "Skip", "SKIP", "关闭广告", "跳过广告"
    );
    private static final Pattern COUNTDOWN_PATTERN = Pattern.compile(".*(跳过|[0-9]\\s*s|关闭).*");

    // 防重入与防抖
    private String lastPackage = "";
    private long lastActionTime = 0;
    private static final long COOLDOWN_MS = 600;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgChar = event.getPackageName();
        if (pkgChar == null) return;
        String currentPackage = pkgChar.toString();

        // 忽略自身以及系统UI桌面
        if (currentPackage.equals(getPackageName()) || 
            currentPackage.contains("launcher") || 
            currentPackage.equals("com.android.systemui")) {
            return;
        }

        // 检查用户白名单（如果加入了白名单则不进行跳过）
        if (AppPreferences.getInstance(this).isPackageWhitelisted(currentPackage)) {
            return;
        }

        // 防误触悬浮透明护盾（若启用，在开屏窗口切换前2秒提供防滑误触保护）
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            AppPreferences.getInstance(this).isTouchShieldEnabled()) {
            TouchShieldManager.getInstance(this).showTemporaryShield(2000);
        }

        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return;

            long now = SystemClock.uptimeMillis();
            if (currentPackage.equals(lastPackage) && (now - lastActionTime < COOLDOWN_MS)) {
                rootNode.recycle();
                return;
            }

            // 深度查找跳过按钮
            boolean clicked = findAndClickSkipNode(rootNode, currentPackage);
            if (clicked) {
                lastPackage = currentPackage;
                lastActionTime = now;
                handleInterceptSuccess(currentPackage);
            }
            rootNode.recycle();
        }
    }

    private boolean findAndClickSkipNode(AccessibilityNodeInfo node, String currentPackage) {
        if (node == null) return false;

        // 1. 文本与描述内容匹配
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if (isSkipTarget(text) || isSkipTarget(desc)) {
            if (performClickOnNode(node)) {
                Log.d(TAG, "成功跳过开屏广告: " + currentPackage + " 文本: " + text);
                return true;
            }
        }

        // 2. 坐标与常见右上角位置探测 (很多App将跳过按钮封装为无文本的小ImageView)
        if ("android.widget.TextView".contentEquals(node.getClassName()) || 
            "android.widget.ImageView".contentEquals(node.getClassName()) ||
            "android.view.View".contentEquals(node.getClassName())) {
            
            CharSequence resId = node.getViewIdResourceName();
            if (resId != null) {
                String idLower = resId.toString().toLowerCase();
                if (idLower.contains("skip") || idLower.contains("splash_ad") || idLower.contains("close_btn")) {
                    if (performClickOnNode(node)) {
                        Log.d(TAG, "通过控件ID成功拦截: " + idLower);
                        return true;
                    }
                }
            }
        }

        // 递归遍历子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean clicked = findAndClickSkipNode(child, currentPackage);
                child.recycle();
                if (clicked) return true;
            }
        }
        return false;
    }

    private boolean isSkipTarget(CharSequence sequence) {
        if (TextUtils.isEmpty(sequence)) return false;
        String text = sequence.toString().trim();
        for (String keyword : SKIP_KEYWORDS) {
            if (text.equalsIgnoreCase(keyword)) {
                return true;
            }
        }
        // 匹配 "跳过 5", "5s 跳过", "跳过广告"
        if (text.length() <= 8 && COUNTDOWN_PATTERN.matcher(text).matches()) {
            return true;
        }
        return false;
    }

    private boolean performClickOnNode(AccessibilityNodeInfo node) {
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        // 若子元素不可点击，向上遍历父控件
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                boolean res = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                return res;
            }
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }
        return false;
    }

    private void handleInterceptSuccess(String packageName) {
        String appName = packageName;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            appName = pm.getApplicationLabel(appInfo).toString();
        } catch (Exception ignored) {
        }

        InterceptDatabaseHelper.getInstance(this).recordIntercept(packageName, appName);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "ClearScreen 无障碍服务被系统中断");
    }
}
