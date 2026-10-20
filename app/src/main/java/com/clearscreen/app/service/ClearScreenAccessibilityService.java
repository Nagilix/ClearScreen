package com.clearscreen.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
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

    // 常见跳过关键词，包含中英文、关闭、符号
    private static final List<String> SKIP_KEYWORDS = Arrays.asList(
            "跳过", "skip", "Skip", "SKIP", "关闭广告", "跳过广告", "关闭"
    );

    // 匹配如: "跳过(3s)", "跳过 5", "5s 跳过", "3秒后跳过", "3s", "关闭"
    private static final Pattern COUNTDOWN_PATTERN = Pattern.compile(".*(跳过|[0-9]\\s*s|[0-9]\\s*秒|关闭).*");

    // 屏蔽词（防止误关正常页面）
    private static final List<String> BLACKLIST_TEXT = Arrays.asList(
            "确定", "提交", "保存", "取消", "登录", "注册", "支付"
    );

    private String lastPackage = "";
    private long lastActionTime = 0;
    private static final long COOLDOWN_MS = 800; // 同一App防抖

    private int screenWidth = 1080;
    private int screenHeight = 2400;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        } catch (Exception ignored) {
        }
        Log.i(TAG, "ClearScreen 无障碍服务已连接，屏幕尺寸: " + screenWidth + "x" + screenHeight);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgChar = event.getPackageName();
        if (pkgChar == null) return;
        String currentPackage = pkgChar.toString();

        // 过滤自身与桌面启动器、系统UI
        if (currentPackage.equals(getPackageName()) || 
            currentPackage.contains("launcher") || 
            currentPackage.equals("com.android.systemui")) {
            return;
        }

        // 过滤白名单
        if (AppPreferences.getInstance(this).isPackageWhitelisted(currentPackage)) {
            return;
        }

        // 防误触护盾
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            AppPreferences.getInstance(this).isTouchShieldEnabled()) {
            TouchShieldManager.getInstance(this).showTemporaryShield(1500);
        }

        int eventType = event.getEventType();
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            long now = SystemClock.uptimeMillis();
            if (currentPackage.equals(lastPackage) && (now - lastActionTime < COOLDOWN_MS)) {
                return;
            }

            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return;

            boolean clicked = scanAndClickTarget(rootNode, currentPackage);
            if (clicked) {
                lastPackage = currentPackage;
                lastActionTime = now;
                handleInterceptSuccess(currentPackage);
            }
            rootNode.recycle();
        }
    }

    private boolean scanAndClickTarget(AccessibilityNodeInfo node, String currentPackage) {
        if (node == null) return false;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence resId = node.getViewIdResourceName();

        // 1. 匹配文本与描述 (跳过、Skip、倒计时)
        if (isSkipTarget(text) || isSkipTarget(desc)) {
            if (executeClick(node)) {
                Log.d(TAG, "成功跳过开屏广告: " + currentPackage + " 文本: " + text + ", 描述: " + desc);
                return true;
            }
        }

        // 2. 匹配关闭/跳过类的 Resource ID
        if (resId != null) {
            String idLower = resId.toString().toLowerCase();
            if (idLower.contains("skip") || idLower.contains("splash_ad") || 
                idLower.contains("close_btn") || idLower.contains("btn_close") ||
                idLower.contains("ad_skip") || idLower.contains("splash_skip") ||
                idLower.contains("iv_close") || idLower.contains("ad_close")) {
                if (executeClick(node)) {
                    Log.d(TAG, "通过控件ID成功拦截: " + idLower);
                    return true;
                }
            }
        }

        // 3. 针对贴吧/酷狗等右上角无文本图标的“X”或圆形按钮特征识别
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (isTopCornerAdCloseBtn(node, bounds)) {
            if (executeClick(node)) {
                Log.d(TAG, "检测到右上角广告关闭控件并点击: " + bounds);
                return true;
            }
        }

        // 递归扫描子节点
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean done = scanAndClickTarget(child, currentPackage);
                child.recycle();
                if (done) return true;
            }
        }

        return false;
    }

    private boolean isSkipTarget(CharSequence sequence) {
        if (TextUtils.isEmpty(sequence)) return false;
        String text = sequence.toString().trim();

        // 排除黑名单敏感词
        for (String black : BLACKLIST_TEXT) {
            if (text.equals(black)) return false;
        }

        for (String keyword : SKIP_KEYWORDS) {
            if (text.equalsIgnoreCase(keyword) || text.contains(keyword)) {
                return true;
            }
        }

        // 匹配长度 <= 10 的字符如 "3s 跳过", "跳过(4)", "关闭 3s"
        if (text.length() <= 10 && COUNTDOWN_PATTERN.matcher(text).matches()) {
            return true;
        }

        // 匹配单独的 "×" 或 "X" 且位于短文本
        if (text.equals("×") || text.equalsIgnoreCase("x") || text.equals("✕") || text.equals("✖")) {
            return true;
        }

        return false;
    }

    /**
     * 判断是否为位于屏幕上半部分/右上角的微型广告关闭控件（贴吧/酷狗/信息流弹窗广告常见形态）
     */
    private boolean isTopCornerAdCloseBtn(AccessibilityNodeInfo node, Rect bounds) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;

        // 宽高一般在 30dp ~ 200dp 之间（像素在 40px ~ 300px 之间）
        boolean isSmallSize = bounds.width() < 300 && bounds.height() < 300 && bounds.width() > 30 && bounds.height() > 30;
        
        // 位于屏幕右上侧（X坐标在屏幕右侧 40% 以上，Y坐标在屏幕顶部 25% 以内）
        boolean isRightTop = bounds.right >= (screenWidth * 0.6) && bounds.top <= (screenHeight * 0.25);

        if (isSmallSize && isRightTop) {
            CharSequence cls = node.getClassName();
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();

            if (cls != null && (cls.toString().contains("ImageView") || cls.toString().contains("TextView") || cls.toString().contains("View"))) {
                // 如果带有明显关闭含义，或没有文本（纯X图）
                if (TextUtils.isEmpty(text) && TextUtils.isEmpty(desc)) {
                    CharSequence id = node.getViewIdResourceName();
                    if (id != null && (id.toString().toLowerCase().contains("close") || id.toString().toLowerCase().contains("ad") || id.toString().toLowerCase().contains("skip"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 执行点击：优先 ACTION_CLICK，回溯父节点，若父节点仍不可点则使用屏幕手势手柄直接在坐标中心模拟触控
     */
    private boolean executeClick(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // 1. 本节点可点击
        if (node.isClickable()) {
            boolean success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (success) return true;
        }

        // 2. 向上回溯父容器尝试点击
        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < 3) {
            if (parent.isClickable()) {
                boolean success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                if (success) return true;
                break;
            }
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
            depth++;
        }

        // 3. 若节点或父节点均未声明 clickable，但属于匹配目标，使用模拟物理手势手柄进行屏幕物理点击（Android 7.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                float clickX = bounds.centerX();
                float clickY = bounds.centerY();
                return clickAtPoint(clickX, clickY);
            }
        }

        return false;
    }

    private boolean clickAtPoint(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path clickPath = new Path();
        clickPath.moveTo(x, y);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 50));
        boolean dispatched = dispatchGesture(gestureBuilder.build(), null, null);
        Log.d(TAG, "坐标物理点击派发 (" + x + ", " + y + "): " + dispatched);
        return dispatched;
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
