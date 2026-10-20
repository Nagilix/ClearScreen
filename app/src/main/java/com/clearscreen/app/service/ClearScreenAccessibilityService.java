package com.clearscreen.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ClearScreenAccessibilityService extends AccessibilityService {

    private static final String TAG = "ClearScreenService";

    // 严谨合规的跳过关键词
    private static final List<String> SKIP_EXACT_KEYWORDS = Arrays.asList(
            "跳过", "skip", "Skip", "SKIP", "跳过广告", "关闭广告"
    );

    // 匹配常规倒计时格式，如 "跳过 5s", "5s 跳过", "3秒后跳过", "4s"
    private static final Pattern COUNTDOWN_PATTERN = Pattern.compile("^(?:\\d{1,2}\\s*s|\\d{1,2}\\s*秒|跳过\\s*\\d{1,2}|\\d{1,2}\\s*跳过|跳过\\s*\\(\\d{1,2}\\))$", Pattern.CASE_INSENSITIVE);

    // 严禁点击的黑名单词汇（防止误伤业务界面）
    private static final Set<String> FORBIDDEN_WORDS = new HashSet<>(Arrays.asList(
            "x", "X", "确定", "取消", "提交", "下一步", "登录", "注册", "分享", "搜索", "消息", "设置", "我的", "首页"
    ));

    // 常见各品牌桌面/系统UI包名黑名单
    private static final Set<String> SYSTEM_PACKAGES = new HashSet<>(Arrays.asList(
            "com.hihonor.android.launcher",
            "com.huawei.android.launcher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.bbk.launcher2",
            "com.sec.android.app.launcher"
    ));

    private final Set<String> launcherPackages = new HashSet<>(SYSTEM_PACKAGES);

    // 记录各应用切到前台的时间戳（开屏检测生命周期限制：仅在进入前台的前 6 秒内生效）
    private final Map<String, Long> appLaunchTimes = new HashMap<>();
    private String currentForegroundPackage = "";
    private long lastActionTime = 0;
    private static final long COOLDOWN_MS = 600;
    private static final long SPLASH_WINDOW_TIMEOUT_MS = 6000; // 开屏有效观察期 6 秒

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
        initHomeLaunchers();
        Log.i(TAG, "ClearScreen 核心防护引擎就绪");
    }

    private void initHomeLaunchers() {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo info : resolveInfos) {
                if (info.activityInfo != null) {
                    launcherPackages.add(info.activityInfo.packageName);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkgChar = event.getPackageName();
        if (pkgChar == null) return;
        String eventPkg = pkgChar.toString();

        // 1. 绝对防御：自身、系统桌面、系统界面一律阻断，杜绝误触桌面上包括名为"X"在内的任何应用图标
        if (eventPkg.equals(getPackageName()) || launcherPackages.contains(eventPkg) || eventPkg.contains("launcher")) {
            return;
        }

        // 2. 检查用户白名单
        if (AppPreferences.getInstance(this).isPackageWhitelisted(eventPkg)) {
            return;
        }

        int eventType = event.getEventType();
        long now = SystemClock.uptimeMillis();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!eventPkg.equals(currentForegroundPackage)) {
                currentForegroundPackage = eventPkg;
                appLaunchTimes.put(eventPkg, now); // 记录冷启动/切前台开始时刻
            }
        }

        // 3. 开屏生命周期限制：只有在该 App 切至前台的 6 秒内才处于“开屏侦测窗口”，超出后自动休眠，绝不干扰应用内正常浏览
        Long launchTime = appLaunchTimes.get(eventPkg);
        if (launchTime == null || (now - launchTime > SPLASH_WINDOW_TIMEOUT_MS)) {
            return;
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {

            // 防抖
            if (now - lastActionTime < COOLDOWN_MS) {
                return;
            }

            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return;

            boolean handled = findAndExecuteSkip(rootNode, eventPkg);
            if (handled) {
                lastActionTime = now;
                // 成功拦截后将该 App 的开屏窗口期直接置为已失效，避免重复点击
                appLaunchTimes.put(eventPkg, 0L);
                handleInterceptSuccess(eventPkg);
            }
            rootNode.recycle();
        }
    }

    private boolean findAndExecuteSkip(AccessibilityNodeInfo node, String pkg) {
        if (node == null) return false;

        CharSequence rawText = node.getText();
        CharSequence rawDesc = node.getContentDescription();
        CharSequence rawId = node.getViewIdResourceName();

        // 1. 文本校验
        if (isValidSkipText(rawText) || isValidSkipText(rawDesc)) {
            if (safePerformClick(node)) {
                Log.d(TAG, "精准命中开屏跳过文本: " + rawText + " / " + rawDesc + ", App: " + pkg);
                return true;
            }
        }

        // 2. 资源 ID 明确指示匹配 (必须严格包含 splash/ad/skip 关键词)
        if (rawId != null) {
            String idLower = rawId.toString().toLowerCase();
            if (idLower.endsWith(":id/skip") || idLower.contains("splash_skip") || 
                idLower.contains("btn_skip") || idLower.contains("skip_view") || 
                idLower.contains("tt_splash_skip_btn") || idLower.contains("splash_ad_skip")) {
                if (safePerformClick(node)) {
                    Log.d(TAG, "精准命中开屏跳过组件ID: " + idLower);
                    return true;
                }
            }
        }

        // 3. 特殊字符与右上角专属关闭符号（✕, ×）匹配，严禁单字符 "x" 或 "X"
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (isSplashCloseSymbol(rawText, rawDesc, bounds)) {
            if (safePerformClick(node)) {
                Log.d(TAG, "精准命中开屏右上角特殊关闭符号: " + bounds);
                return true;
            }
        }

        // 递归子节点
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean done = findAndExecuteSkip(child, pkg);
                child.recycle();
                if (done) return true;
            }
        }

        return false;
    }

    private boolean isValidSkipText(CharSequence sequence) {
        if (TextUtils.isEmpty(sequence)) return false;
        String text = sequence.toString().trim();

        // 黑名单单字阻断，绝不允许 "X" 作为有效跳过词
        if (FORBIDDEN_WORDS.contains(text) || text.length() > 10) {
            return false;
        }

        // 精确包含关键词
        for (String kw : SKIP_EXACT_KEYWORDS) {
            if (text.equalsIgnoreCase(kw) || text.contains(kw)) {
                return true;
            }
        }

        // 倒计时匹配
        return COUNTDOWN_PATTERN.matcher(text).matches();
    }

    /**
     * 仅匹配特殊符号 '✕' / '×'，并且必须严格限制在屏幕右上角（X > 60% 宽度，Y < 20% 高度）且面积很小
     */
    private boolean isSplashCloseSymbol(CharSequence text, CharSequence desc, Rect bounds) {
        String t = text != null ? text.toString().trim() : "";
        String d = desc != null ? desc.toString().trim() : "";

        boolean isSymbol = t.equals("✕") || t.equals("×") || t.equals("✖") ||
                           d.equals("✕") || d.equals("×") || d.equals("✖");

        if (isSymbol) {
            // 严格右上角限定
            return bounds.right >= (screenWidth * 0.6) && 
                   bounds.top <= (screenHeight * 0.20) &&
                   bounds.width() > 30 && bounds.width() < 240;
        }
        return false;
    }

    private boolean safePerformClick(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // 1. 无障碍常规点击
        if (node.isClickable()) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }

        // 2. 向上回溯父容器尝试点击
        AccessibilityNodeInfo parent = node.getParent();
        for (int i = 0; i < 2 && parent != null; i++) {
            if (parent.isClickable()) {
                boolean res = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                if (res) return true;
                break;
            }
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }

        // 3. 屏幕绝对坐标模拟敲击
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                return performTouch(bounds.centerX(), bounds.centerY());
            }
        }

        return false;
    }

    private boolean performTouch(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 40));
        return dispatchGesture(builder.build(), null, null);
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
        Log.w(TAG, "ClearScreen 服务被系统暂停");
    }
}
