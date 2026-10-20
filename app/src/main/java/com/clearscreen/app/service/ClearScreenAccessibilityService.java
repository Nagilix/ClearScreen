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
import android.view.accessibility.AccessibilityWindowInfo;

import com.clearscreen.app.data.AppPreferences;
import com.clearscreen.app.data.InterceptDatabaseHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class ClearScreenAccessibilityService extends AccessibilityService {

    private static final String TAG = "ClearScreenService";

    // 严谨合规的跳过关键词
    private static final List<String> EXACT_SKIP_TEXTS = Arrays.asList(
            "跳过", "skip", "Skip", "SKIP", "跳过广告", "关闭广告"
    );

    // 常见倒计时格式：如 "跳过 5s", "5s 跳过", "3秒后跳过", "4s", "跳过(3)"
    private static final Pattern COUNTDOWN_REGEX = Pattern.compile(
            "^(?:\\d{1,2}\\s*s|\\d{1,2}\\s*秒|跳过\\s*\\d{1,2}|\\d{1,2}\\s*跳过|跳过\\s*\\(\\d{1,2}\\)|跳过\\s*\\d{1,2}\\s*s)$",
            Pattern.CASE_INSENSITIVE
    );

    // 严禁点击的黑名单词汇（杜绝误碰业务按钮与桌面应用名称）
    private static final Set<String> FORBIDDEN_WORDS = new HashSet<>(Arrays.asList(
            "x", "X", "确定", "取消", "提交", "下一步", "登录", "注册", "分享", "搜索", "消息", "设置", "我的", "首页", "关注", "推荐"
    ));

    // 系统桌面包名动态缓存
    private final Set<String> launcherPackages = new HashSet<>(Arrays.asList(
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

    // 开屏生命周期：仅在前台启动后的 8 秒内生效
    private static final long SPLASH_MAX_DURATION_MS = 8000;
    private final Map<String, Long> appLaunchTimes = new HashMap<>();
    private String currentPackage = "";
    private long lastActionTime = 0;
    private static final long COOLDOWN_MS = 500;

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
        initLaunchers();
        Log.i(TAG, "ClearScreen 核心拦截引擎已连接，分辨率: " + screenWidth + "x" + screenHeight);
    }

    private void initLaunchers() {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo info : list) {
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

        CharSequence eventPkgChar = event.getPackageName();
        if (eventPkgChar == null) return;
        String eventPkg = eventPkgChar.toString();

        // 1. 绝对防御：系统桌面、锁屏、清屏自身坚决不碰
        if (eventPkg.equals(getPackageName()) || launcherPackages.contains(eventPkg) || eventPkg.contains("launcher")) {
            return;
        }

        // 2. 白名单检查
        if (AppPreferences.getInstance(this).isPackageWhitelisted(eventPkg)) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        int eventType = event.getEventType();

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!eventPkg.equals(currentPackage)) {
                currentPackage = eventPkg;
                appLaunchTimes.put(eventPkg, now); // 记录冷启动开始时间
            }
        }

        // 3. 开屏生命周期约束：只在该 App 刚打开的前 8 秒内寻找开屏跳过
        Long launchTime = appLaunchTimes.get(eventPkg);
        if (launchTime == null || (now - launchTime > SPLASH_MAX_DURATION_MS)) {
            return;
        }

        // 4. 触发防抖
        if (now - lastActionTime < COOLDOWN_MS) {
            return;
        }

        // 5. 多窗口深度检索：很多应用（如酷狗、百度贴吧、微信、抖音）的开屏是挂在独立 Window 或 Dialog 上的
        List<AccessibilityNodeInfo> roots = getScanRoots(event);
        for (AccessibilityNodeInfo root : roots) {
            if (root == null) continue;
            boolean clicked = scanAndExecuteSkip(root, eventPkg);
            root.recycle();
            if (clicked) {
                lastActionTime = now;
                // 成功跳过后关闭观察期
                appLaunchTimes.put(eventPkg, 0L);
                handleInterceptSuccess(eventPkg);
                break;
            }
        }
    }

    private List<AccessibilityNodeInfo> getScanRoots(AccessibilityEvent event) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        // 尝试获取所有可用交互窗口 (GKD 成熟模式)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null && !windows.isEmpty()) {
                    for (AccessibilityWindowInfo win : windows) {
                        AccessibilityNodeInfo node = win.getRoot();
                        if (node != null) {
                            roots.add(node);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null) roots.add(activeRoot);
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && !roots.contains(source)) roots.add(source);
        }
        return roots;
    }

    private boolean scanAndExecuteSkip(AccessibilityNodeInfo node, String pkg) {
        if (node == null) return false;

        // 规则 1：通用包含匹配：寻找包含“跳过”且文本长度受控的 TextView
        List<AccessibilityNodeInfo> skipNodes = node.findAccessibilityNodeInfosByText("跳过");
        if (skipNodes != null && !skipNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : skipNodes) {
                if (isValidSkipNode(n)) {
                    if (clickNodeWithFallback(n)) {
                        Log.d(TAG, "通过 findAccessibilityNodeInfosByText 成功命中跳过: " + pkg);
                        recycleList(skipNodes);
                        return true;
                    }
                }
            }
            recycleList(skipNodes);
        }

        // 规则 2：英文 "Skip" 匹配
        List<AccessibilityNodeInfo> engSkipNodes = node.findAccessibilityNodeInfosByText("Skip");
        if (engSkipNodes != null && !engSkipNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : engSkipNodes) {
                if (isValidSkipNode(n)) {
                    if (clickNodeWithFallback(n)) {
                        Log.d(TAG, "通过 Skip 成功命中: " + pkg);
                        recycleList(engSkipNodes);
                        return true;
                    }
                }
            }
            recycleList(engSkipNodes);
        }

        // 规则 3：针对酷狗音乐、贴吧等通过 resource-id 或快照选择器匹配
        return deepTraverseNode(node, pkg);
    }

    private boolean deepTraverseNode(AccessibilityNodeInfo node, String pkg) {
        if (node == null) return false;

        CharSequence rawId = node.getViewIdResourceName();
        if (rawId != null) {
            String idLower = rawId.toString().toLowerCase();
            // 穿山甲、优量汇、百度联盟及主流开屏 SDK 的经典 ID
            if (idLower.endsWith(":id/skip") || idLower.contains("splash_skip") ||
                idLower.contains("btn_skip") || idLower.contains("skip_view") ||
                idLower.contains("tt_splash_skip_btn") || idLower.contains("splash_ad_skip") ||
                idLower.contains("iv_skip") || idLower.contains("ad_skip")) {
                if (clickNodeWithFallback(node)) {
                    Log.d(TAG, "通过典型广告SDK ResourceId命中: " + idLower);
                    return true;
                }
            }
        }

        // 规则 4：贴吧等特定开屏右上角的关闭图标（限制极小区域）
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (isSplashTopCornerSymbol(node, bounds)) {
            if (clickNodeWithFallback(node)) {
                Log.d(TAG, "通过开屏角标符号命中: " + bounds);
                return true;
            }
        }

        // 递归遍历子项
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean done = deepTraverseNode(child, pkg);
                child.recycle();
                if (done) return true;
            }
        }
        return false;
    }

    private boolean isValidSkipNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence seqText = node.getText();
        CharSequence seqDesc = node.getContentDescription();

        String text = seqText != null ? seqText.toString().trim() : "";
        String desc = seqDesc != null ? seqDesc.toString().trim() : "";

        return checkTextSemantics(text) || checkTextSemantics(desc);
    }

    private boolean checkTextSemantics(String str) {
        if (TextUtils.isEmpty(str) || FORBIDDEN_WORDS.contains(str) || str.length() > 10) {
            return false;
        }

        // 精确匹配“跳过”、“Skip”等
        for (String exact : EXACT_SKIP_TEXTS) {
            if (str.equalsIgnoreCase(exact) || str.startsWith(exact)) {
                return true;
            }
        }

        // 匹配倒计时组合
        return COUNTDOWN_REGEX.matcher(str).matches();
    }

    /**
     * 针对无文字的右上角小关闭按钮，进行严格的位置和几何约束
     */
    private boolean isSplashTopCornerSymbol(AccessibilityNodeInfo node, Rect bounds) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;

        // 必须位于屏幕右上角 25% 范围内，尺寸小于 200px
        boolean isRightTop = bounds.right >= (screenWidth * 0.65) && bounds.top <= (screenHeight * 0.20);
        boolean isSmall = bounds.width() < 240 && bounds.height() < 240 && bounds.width() > 30;

        if (isRightTop && isSmall) {
            CharSequence id = node.getViewIdResourceName();
            if (id != null) {
                String idLower = id.toString().toLowerCase();
                return idLower.contains("close") || idLower.contains("skip") || idLower.contains("ad");
            }
            CharSequence text = node.getText();
            if (text != null) {
                String t = text.toString().trim();
                return t.equals("✕") || t.equals("×") || t.equals("✖");
            }
        }
        return false;
    }

    /**
     * 执行点击：成熟的三层退化方案（仿 Android-Touch-Helper & GKD）
     * 1. 尝试本节点 performAction(ACTION_CLICK)
     * 2. 向上回溯父级 FrameLayout / RelativeLayout 触发
     * 3. 终极坐标手势模拟物理点击 (击穿 clickable=false 的广告保护)
     */
    private boolean clickNodeWithFallback(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // 1. 直接点击
        if (node.isClickable()) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
        }

        // 2. 回溯父节点
        AccessibilityNodeInfo parent = node.getParent();
        for (int i = 0; i < 3 && parent != null; i++) {
            if (parent.isClickable()) {
                boolean ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                if (ok) return true;
                break;
            }
            AccessibilityNodeInfo grandParent = parent.getParent();
            parent.recycle();
            parent = grandParent;
        }

        // 3. 底层物理手势敲击屏幕中心绝对像素点
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0) {
                return dispatchPhysicalTouch(bounds.centerX(), bounds.centerY());
            }
        }

        return false;
    }

    private boolean dispatchPhysicalTouch(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path touchPath = new Path();
        touchPath.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(touchPath, 0, 50));
        return dispatchGesture(builder.build(), null, null);
    }

    private void recycleList(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo n : list) {
            if (n != null) n.recycle();
        }
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
        Log.w(TAG, "ClearScreen 无障碍服务被暂停");
    }
}
