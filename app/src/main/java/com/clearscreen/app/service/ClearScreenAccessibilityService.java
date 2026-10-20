package com.clearscreen.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class ClearScreenAccessibilityService extends AccessibilityService {

    private static final String TAG = "ClearScreenService";

    // 严禁误点的词（防止在系统桌面、聊天或业务界面误点）
    private static final Set<String> FORBIDDEN_WORDS = new HashSet<>(Arrays.asList(
            "x", "X", "确定", "取消", "提交", "下一步", "登录", "注册", "分享", "搜索", "消息", "设置", "我的", "首页", "关注", "推荐", "发送"
    ));

    // 倒计时匹配：如 5s, 3秒, 跳过 4s, 5s跳过
    private static final Pattern COUNTDOWN_REGEX = Pattern.compile(
            ".*(?:\\d\\s*s|\\d\\s*秒|跳过|skip|关闭).*",
            Pattern.CASE_INSENSITIVE
    );

    // 桌面包名集合（动态+静态）
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

    private long lastActionTime = 0;
    private static final long COOLDOWN_MS = 350;

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

        // 强行在运行时通过代码配置 ServiceInfo（彻底绕过某些系统在解析 xml 时的阉割问题）
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                info = new AccessibilityServiceInfo();
            }
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.notificationTimeout = 20;
            setServiceInfo(info);
        } catch (Exception e) {
            Log.e(TAG, "配置 ServiceInfo 异常: ", e);
        }

        initLaunchers();
        Log.i(TAG, "ClearScreen 无障碍核心服务已稳健挂载！");
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
        if (now - lastActionTime < COOLDOWN_MS) {
            return;
        }

        // 3. 聚合所有可能窗口与节点（彻底解决多 Window 广告）
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo win : windows) {
                        AccessibilityNodeInfo node = win.getRoot();
                        if (node != null) roots.add(node);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null && !roots.contains(activeRoot)) roots.add(activeRoot);
        AccessibilityNodeInfo sourceNode = event.getSource();
        if (sourceNode != null && !roots.contains(sourceNode)) roots.add(sourceNode);

        for (AccessibilityNodeInfo root : roots) {
            if (root == null) continue;
            boolean clicked = findAndClickSkip(root, eventPkg);
            root.recycle();
            if (clicked) {
                lastActionTime = now;
                handleInterceptSuccess(eventPkg);
                break;
            }
        }
    }

    private boolean findAndClickSkip(AccessibilityNodeInfo node, String pkg) {
        if (node == null) return false;

        // 策略 A：直接原生匹配包含“跳过”的控件
        List<AccessibilityNodeInfo> list = node.findAccessibilityNodeInfosByText("跳过");
        if (list != null && !list.isEmpty()) {
            for (AccessibilityNodeInfo n : list) {
                if (isValidSkipNode(n)) {
                    if (clickNodeWithGesture(n)) {
                        Log.d(TAG, "成功跳过广告 [文本包含跳过]: " + pkg);
                        recycleNodes(list);
                        return true;
                    }
                }
            }
            recycleNodes(list);
        }

        // 策略 B：英文 Skip 匹配
        List<AccessibilityNodeInfo> skipEngList = node.findAccessibilityNodeInfosByText("Skip");
        if (skipEngList != null && !skipEngList.isEmpty()) {
            for (AccessibilityNodeInfo n : skipEngList) {
                if (isValidSkipNode(n)) {
                    if (clickNodeWithGesture(n)) {
                        Log.d(TAG, "成功跳过广告 [英文Skip]: " + pkg);
                        recycleNodes(skipEngList);
                        return true;
                    }
                }
            }
            recycleNodes(skipEngList);
        }

        // 策略 C：全树扫描特征（ID、倒计时文字、角标 X）
        return deepInspectNode(node, pkg);
    }

    private boolean deepInspectNode(AccessibilityNodeInfo node, String pkg) {
        if (node == null) return false;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence resId = node.getViewIdResourceName();

        // 检查文本是否满足“3s跳过”、“跳过 5s”等
        if (isTextMatched(text) || isTextMatched(desc)) {
            if (clickNodeWithGesture(node)) {
                Log.d(TAG, "成功跳过广告 [正则倒计时]: " + pkg);
                return true;
            }
        }

        // 检查资源 ID（穿山甲、优量汇、百度、快手等广告联盟经典ID）
        if (resId != null) {
            String id = resId.toString().toLowerCase();
            if (id.contains("skip") || id.contains("splash_ad") || id.contains("tt_splash_skip") ||
                id.contains("btn_close") || id.contains("iv_close") || id.contains("ad_close")) {
                if (clickNodeWithGesture(node)) {
                    Log.d(TAG, "成功跳过广告 [资源ID]: " + id + ", App: " + pkg);
                    return true;
                }
            }
        }

        // 检查贴吧/酷狗等右上角的无字关闭角标或特殊符号
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (isCornerCloseBtn(node, bounds)) {
            if (clickNodeWithGesture(node)) {
                Log.d(TAG, "成功跳过广告 [右上角关闭按钮]: " + bounds);
                return true;
            }
        }

        // 递归扫描所有子视图
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean done = deepInspectNode(child, pkg);
                child.recycle();
                if (done) return true;
            }
        }
        return false;
    }

    private boolean isTextMatched(CharSequence sequence) {
        if (TextUtils.isEmpty(sequence)) return false;
        String s = sequence.toString().trim();
        if (FORBIDDEN_WORDS.contains(s) || s.length() > 8) return false;

        if (s.contains("跳过") || s.equalsIgnoreCase("skip") || s.contains("关闭广告")) {
            return true;
        }
        return COUNTDOWN_REGEX.matcher(s).matches();
    }

    private boolean isValidSkipNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        return isTextMatched(t) || isTextMatched(d);
    }

    private boolean isCornerCloseBtn(AccessibilityNodeInfo node, Rect bounds) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;

        // 屏幕右上角 35% 宽度，顶部 25% 高度
        boolean inRightTop = bounds.right >= (screenWidth * 0.65) && bounds.top <= (screenHeight * 0.25);
        boolean isSmallSize = bounds.width() < 240 && bounds.height() < 240 && bounds.width() > 30;

        if (inRightTop && isSmallSize) {
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
     * 关键执行器：双重保证（常规点击 + 物理触控强行派发）
     */
    private boolean clickNodeWithGesture(AccessibilityNodeInfo node) {
        if (node == null) return false;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();

        // 1. 无障碍标准点击
        if (node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }

        // 2. 父节点向上寻找点击
        AccessibilityNodeInfo p = node.getParent();
        for (int i = 0; i < 2 && p != null; i++) {
            if (p.isClickable()) {
                p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                p.recycle();
                break;
            }
            AccessibilityNodeInfo next = p.getParent();
            p.recycle();
            p = next;
        }

        // 3. 【核心救命符】：物理屏幕坐标直接派发敲击（Android 7.0+）
        // 百度贴吧、酷狗音乐等几乎所有穿山甲/优量汇广告都用此方式秒破
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && bounds.width() > 0 && bounds.height() > 0) {
            return performDirectTouch(centerX, centerY);
        }

        return true;
    }

    private boolean performDirectTouch(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        return dispatchGesture(builder.build(), null, null);
    }

    private void recycleNodes(List<AccessibilityNodeInfo> list) {
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
        Log.w(TAG, "ClearScreen 服务中断");
    }
}
