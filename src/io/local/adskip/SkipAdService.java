package io.local.adskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 核心无障碍服务（v0.0.4 纯静默无感 + 安全防御加固版）。
 *
 * 专属安全防护设计：
 * 1. 【接口物理隔离】：android:exported="false" + BIND_ACCESSIBILITY_SERVICE 签名权限，
 *    彻底阻断任何第三方恶意 App 通过跨进程 Intent 攻击或利用本服务；
 * 2. 【高危系统组件黑名单】：绝对不响应系统权限弹窗(PermissionController)、安装器(PackageInstaller)、
 *    锁屏密码(Keyguard)等，严防恶意软件借壳提权或静默安装；
 * 3. 【5秒开屏时间窗防线】：只有在 App 切换/打开的前 5 秒内才允许寻找广告按钮，
 *    应用正常运行后自动完全静默，彻底杜绝正文/文章/聊天/内购页面钓鱼诱导点击；
 * 4. 【敏感控件免疫】：对任何密码框(isPassword)、文本输入框(isEditable)绝对不处理、不点击；
 * 5. 【纯本地零网络】：不申请网络权限，不保存任何屏幕内容，纯内存即时判定，不留存任何隐私。
 */
public class SkipAdService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    public static final String PREF_NAME = "adskip_stats";
    public static final String KEY_SKIP_COUNT = "skip_count";

    /** 开屏广告最大生命周期窗口：应用切到前台 5 秒后，绝不再执行跳过，防正文诱骗点击 */
    private static final long APP_OPEN_WINDOW_MS = 5000;

    /**
     * 高危系统安全组件 + 免打扰系统包名黑名单：
     * 涉及系统权限、应用安装、锁屏、支付鉴权等核心环节，绝对零参与、零干扰。
     */
    private static final Set<String> SECURITY_IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "io.local.adskip",
            "com.android.systemui",
            "com.miui.home",                       // 小米系统桌面
            "com.miui.personalassistant",          // 负一屏
            "com.android.settings",                // 系统设置
            "com.miui.securitycenter",             // 手机管家/安全中心
            "com.miui.notification",               // 通知中心
            "com.android.incallui",                // 通话界面
            "com.android.permissioncontroller",    // 系统权限授予窗口（防恶意自动授权）
            "com.miui.packageinstaller",           // 小米安装器（防流氓软件静默安装）
            "com.android.packageinstaller",        // 原生安装器
            "com.google.android.packageinstaller", // 谷歌安装器
            "com.android.keyguard",                // 锁屏密码凭据
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.bytedance.android.doubaoime"
    ));

    /** 粗筛正则：匹配“跳过/跳 过/关闭广告/Skip/Skip Ad”等各种变体 */
    private static final Pattern SKIP_BROAD = Pattern.compile(
            "跳\\s*过|关闭广告|skip(?:\\s*ads?)?",
            Pattern.CASE_INSENSITIVE);

    /** 严格手势正则：小尺寸坐标点击兜底专用，防止误触页面正文 */
    private static final Pattern SKIP_STRICT = Pattern.compile(
            "(?:点击)?\\s*跳\\s*过(?:\\s*广告)?(?:\\s*[\\dxsXsS秒]*)"
                    + "|\\d{1,3}\\s*[sS秒]?\\s*[|｜·,，\\-]?\\s*跳\\s*过(?:\\s*广告)?"
                    + "|跳\\s*过(?:\\s*广告)?\\s*[|｜·,，\\-]?\\s*\\d{1,3}\\s*[sS秒]?"
                    + "|skip(?:\\s*(?:this\\s*)?ads?)?\\s*(?:now)?"
                    + "|关闭广告",
            Pattern.CASE_INSENSITIVE);

    /** 开屏新窗口事件的三段爆发脉冲探测间隔（毫秒） */
    private static final long[] PULSE_DELAYS = {0, 120, 280};

    /** 常规内容变更的退避参数 */
    private static final long BASE_SCAN_MS = 250;
    private static final long MAX_SCAN_MS = 2000;
    private static final int MAX_BACKOFF_SHIFT = 3;

    /** 成功点击后的全局冷却时间 */
    private static final long CLICK_COOLDOWN_MS = 2000;

    /** 遍历防护上限 */
    private static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 40;
    private static final int MAX_TEXT_LEN = 24;
    private static final int MAX_ANCESTOR_DEPTH = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> pendingPulseTasks = new ArrayList<>();

    private String currentForegroundPkg = "";
    private long currentPkgAppearedTime;
    private long lastClickUptime;
    private long misses;
    private boolean regularScanScheduled;
    private long regularScanDueAt;

    private final Runnable regularScanTask = new Runnable() {
        @Override
        public void run() {
            regularScanScheduled = false;
            scanAndClick();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Toast.makeText(getApplicationContext(), "跳跳助手 (0.0.4) 安全无感运行中", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        boolean windowChanged = (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        if (!windowChanged && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        // 1. 安全过滤：高危系统组件、权限弹窗、安装器、系统桌面直接丢弃
        if (SECURITY_IGNORED_PACKAGES.contains(pkg)) {
            return;
        }

        long now = SystemClock.uptimeMillis();

        // 2. 时间窗口防线：维护应用切换时间戳
        if (windowChanged) {
            if (!pkg.equals(currentForegroundPkg)) {
                currentForegroundPkg = pkg;
                currentPkgAppearedTime = now;
            }
        }

        // 超过 5 秒后，应用处于正常交互阶段，绝不再响应跳过，杜绝钓鱼
        if (now - currentPkgAppearedTime > APP_OPEN_WINDOW_MS) {
            return;
        }

        if (now - lastClickUptime < CLICK_COOLDOWN_MS) {
            return;
        }

        if (windowChanged) {
            // 新窗口爆发脉冲（0ms / 120ms / 280ms）
            scheduleBurstPulses();
        } else {
            // 开屏 5 秒内的内容变动：节流调度
            scheduleThrottledScan(now);
        }
    }

    private void scheduleBurstPulses() {
        cancelAllPendingTasks();
        misses = 0;
        for (long delay : PULSE_DELAYS) {
            Runnable pulse = new Runnable() {
                @Override
                public void run() {
                    scanAndClick();
                }
            };
            pendingPulseTasks.add(pulse);
            handler.postDelayed(pulse, delay);
        }
    }

    private void scheduleThrottledScan(long now) {
        long delay = Math.min(BASE_SCAN_MS << (int) Math.min(misses, MAX_BACKOFF_SHIFT), MAX_SCAN_MS);
        long due = now + delay;
        if (!regularScanScheduled || due < regularScanDueAt) {
            handler.removeCallbacks(regularScanTask);
            handler.postDelayed(regularScanTask, delay);
            regularScanDueAt = due;
            regularScanScheduled = true;
        }
    }

    private void cancelAllPendingTasks() {
        for (Runnable r : pendingPulseTasks) {
            handler.removeCallbacks(r);
        }
        pendingPulseTasks.clear();
        handler.removeCallbacks(regularScanTask);
        regularScanScheduled = false;
    }

    private void scanAndClick() {
        long now = SystemClock.uptimeMillis();
        // 二次双保险：必须在 5 秒开屏窗口内，且不在冷却期内
        if (now - currentPkgAppearedTime > APP_OPEN_WINDOW_MS) return;
        if (now - lastClickUptime < CLICK_COOLDOWN_MS) return;

        AccessibilityNodeInfo root = getRootInActiveWindow(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID);
        if (root == null) return;

        visited = 0;
        boolean clicked = search(root, 0);

        if (clicked) {
            misses = 0;
            lastClickUptime = now;
            cancelAllPendingTasks();
            incrementSkipCount();
        } else {
            misses++;
        }
    }

    private int visited;

    /** 倒序深度优先遍历 + 敏感控件免疫检查 */
    private boolean search(AccessibilityNodeInfo node, int depth) {
        if (node == null || visited++ >= MAX_NODES || depth > MAX_DEPTH) return false;

        // 安全防线：绝对不触碰密码框、账号输入框
        if (node.isPassword() || node.isEditable()) {
            return false;
        }

        CharSequence text = node.getText();
        if (text == null || text.length() == 0) text = node.getContentDescription();
        if (text != null && text.length() > 0 && text.length() <= MAX_TEXT_LEN
                && SKIP_BROAD.matcher(text).find()) {
            if (clickNode(node)) return true;
        }

        int count = node.getChildCount();
        // 倒序遍历子节点，极速优先命中顶层浮层
        for (int i = count - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && search(child, depth + 1)) return true;
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node.isPassword() || node.isEditable()) return false;

        if (node.isClickable()) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "成功跳过广告 (节点自身点击)");
                return true;
            }
        }

        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
            if (parent.isPassword() || parent.isEditable()) return false;
            if (parent.isClickable()) {
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "成功跳过广告 (祖先节点点击)");
                    return true;
                }
                break;
            }
            parent = parent.getParent();
            depth++;
        }

        if (gestureTapIfButtonLike(node)) {
            Log.i(TAG, "成功跳过广告 (坐标模拟点击)");
            return true;
        }
        return false;
    }

    private boolean gestureTapIfButtonLike(AccessibilityNodeInfo node) {
        if (node.isPassword() || node.isEditable()) return false;

        CharSequence text = node.getText();
        if (text == null || text.length() == 0) text = node.getContentDescription();
        if (text == null || text.length() > 12) return false;
        if (!SKIP_STRICT.matcher(text).matches()) return false;

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0 || r.height() > 220) return false;

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (r.width() > sw / 2 || r.height() > sh / 4) return false;
        if (r.left < 0 || r.top < 0 || r.right > sw || r.bottom > sh) return false;

        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 35);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private void incrementSkipCount() {
        try {
            SharedPreferences sp = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            long count = sp.getLong(KEY_SKIP_COUNT, 0) + 1;
            sp.edit().putLong(KEY_SKIP_COUNT, count).apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cancelAllPendingTasks();
        Toast.makeText(getApplicationContext(), "跳跳助手服务已停止", Toast.LENGTH_SHORT).show();
        return super.onUnbind(intent);
    }
}
