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
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 核心无障碍服务（v0.0.5 深度穿透 + 开屏守护巡检优化版）。
 *
 * 核心优化：
 * 1. 【开屏冲刺守护巡检】：新应用打开前 2.5 秒内，以阶梯间隔主动巡检，
 *    彻底搞定网易云、优酷等冷启动 1~2 秒后才异步出广告且不发事件的 App；
 * 2. 【多窗口穿透】：getWindows() 穿透浮层 Window 检索穿山甲/优量汇独立弹窗；
 * 3. 【适配 2K 高清屏】：修正 bounds 高度限制，支持 1440x3200 大尺寸按钮识别；
 * 4. 【多行文本与非标文案清洗】：兼容倒计时换行(如 3s\n跳过广告)及多空格形态；
 * 5. 【单次跳过即静默】：当前 App 跳过成功后立即取消一切任务，零冗余开销。
 */
public class SkipAdService extends AccessibilityService {

    private static final String TAG = "SkipAdService";
    public static final String PREF_NAME = "adskip_stats";
    public static final String KEY_SKIP_COUNT = "skip_count";

    /** 开屏广告最大生命周期窗口（5秒内允许，之后彻底静默防误触） */
    private static final long APP_OPEN_WINDOW_MS = 5000;

    /** 新应用冷启动主动巡检时间轴（毫秒）：应对冷启动异步加载无事件的广告 */
    private static final long[] LAUNCH_GUARD_DELAYS = {0, 150, 350, 700, 1100, 1600, 2200};

    /** 高危系统组件 + 系统桌面黑名单 */
    private static final Set<String> SECURITY_IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "io.local.adskip",
            "com.android.systemui",
            "com.miui.home",                       // 系统桌面
            "com.miui.personalassistant",          // 负一屏
            "com.android.settings",                // 系统设置
            "com.miui.securitycenter",             // 手机管家/安全中心
            "com.miui.notification",               // 通知中心
            "com.android.incallui",                // 通话界面
            "com.android.permissioncontroller",    // 系统权限授予窗口
            "com.miui.packageinstaller",           // 应用安装器
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.keyguard",                // 锁屏密码凭据
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.bytedance.android.doubaoime"
    ));

    /** 粗筛正则：匹配“跳过/跳 过/关闭广告/Skip/Skip Ad/3 跳过广告”等变体 */
    private static final Pattern SKIP_BROAD = Pattern.compile(
            "跳\\s*过|关闭广告|skip(?:\\s*ads?)?",
            Pattern.CASE_INSENSITIVE);

    /** 严格手势正则：小尺寸坐标点击兜底专用 */
    private static final Pattern SKIP_STRICT = Pattern.compile(
            "(?:点击)?\\s*跳\\s*过(?:\\s*广告)?(?:\\s*[\\dxsXsS秒]*)"
                    + "|\\d{1,3}\\s*[sS秒]?\\s*[|｜·,，\\-\\s]*跳\\s*过(?:\\s*广告)?"
                    + "|跳\\s*过(?:\\s*广告)?\\s*[|｜·,，\\-\\s]*\\d{1,3}\\s*[sS秒]?"
                    + "|skip(?:\\s*(?:this\\s*)?ads?)?\\s*(?:now)?"
                    + "|关闭广告",
            Pattern.CASE_INSENSITIVE);

    /** 成功点击后的全局冷却时间 */
    private static final long CLICK_COOLDOWN_MS = 2000;

    private static final int MAX_NODES = 1500;
    private static final int MAX_DEPTH = 40;
    private static final int MAX_TEXT_LEN = 25;
    private static final int MAX_ANCESTOR_DEPTH = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String currentForegroundPkg = "";
    private long currentPkgAppearedTime;
    private boolean hasSkippedForCurrentApp;
    private long lastClickUptime;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "跳跳助手 (0.0.5) 极速守护模式已就绪");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        // 1. 安全过滤
        if (SECURITY_IGNORED_PACKAGES.contains(pkg)) {
            return;
        }

        long now = SystemClock.uptimeMillis();

        // 2. 识别前台 App 切换
        boolean isNewApp = !pkg.equals(currentForegroundPkg);
        if (isNewApp) {
            currentForegroundPkg = pkg;
            currentPkgAppearedTime = now;
            hasSkippedForCurrentApp = false;
            Log.i(TAG, ">>> 发现新应用切入前台: " + pkg + "，启动开屏守护巡检");
            // 启动开屏守护巡检队列（应对异步延迟出广告的 App）
            startLaunchGuard();
            return;
        }

        // 如果在当前应用已经成功跳过，或者进入正常使用阶段（超过5秒），静默不处理
        if (hasSkippedForCurrentApp || (now - currentPkgAppearedTime > APP_OPEN_WINDOW_MS)) {
            return;
        }

        if (now - lastClickUptime < CLICK_COOLDOWN_MS) {
            return;
        }

        // 响应当前窗口的即时事件
        scanAndClick();
    }

    /** 启动冷启动守护巡检队列：在冷启动 0~2.2s 密集探测 */
    private void startLaunchGuard() {
        handler.removeCallbacksAndMessages(null);
        for (long delay : LAUNCH_GUARD_DELAYS) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!hasSkippedForCurrentApp) {
                        scanAndClick();
                    }
                }
            }, delay);
        }
    }

    /** 核心扫描入口：多窗口穿透 */
    private void scanAndClick() {
        long now = SystemClock.uptimeMillis();
        if (hasSkippedForCurrentApp || (now - currentPkgAppearedTime > APP_OPEN_WINDOW_MS)) {
            return;
        }
        if (now - lastClickUptime < CLICK_COOLDOWN_MS) {
            return;
        }

        // 1. 尝试主窗口
        AccessibilityNodeInfo root = getRootInActiveWindow(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID);
        if (root != null) {
            visited = 0;
            if (search(root, 0)) {
                onAdSkipped(now);
                return;
            }
        }

        // 2. 穿透屏幕所有窗口（应对独立弹窗/浮层广告）
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo win : windows) {
                    AccessibilityNodeInfo winRoot = win.getRoot();
                    if (winRoot != null) {
                        visited = 0;
                        if (search(winRoot, 0)) {
                            onAdSkipped(now);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void onAdSkipped(long now) {
        lastClickUptime = now;
        hasSkippedForCurrentApp = true;
        // 跳过成功后，立即取消后续所有巡检任务，彻底静默
        handler.removeCallbacksAndMessages(null);
        incrementSkipCount();
        Log.i(TAG, "★ 成功跳过开屏广告，当前应用进入完全静默状态！");
    }

    private int visited;

    /** 倒序深度优先遍历：优先扫描最顶层浮层控件，毫秒级命中 */
    private boolean search(AccessibilityNodeInfo node, int depth) {
        if (node == null || visited++ >= MAX_NODES || depth > MAX_DEPTH) return false;

        // 安全防线：绝对不触碰密码框、文本输入框
        if (node.isPassword() || node.isEditable()) {
            return false;
        }

        CharSequence text = node.getText();
        if (text == null || text.length() == 0) text = node.getContentDescription();
        if (text != null && text.length() > 0 && text.length() <= MAX_TEXT_LEN) {
            // 清洗多余空格和换行符 (如 "3s\n跳过广告")
            String cleanText = text.toString().replaceAll("[\\r\\n\\s]+", " ").trim();
            if (SKIP_BROAD.matcher(cleanText).find()) {
                Log.i(TAG, "发现匹配广告文案: [" + cleanText + "] id=" + node.getViewIdResourceName());
                if (clickNode(node, cleanText)) return true;
            }
        }

        int count = node.getChildCount();
        // 倒序遍历子节点，开屏跳过浮层位于末尾子节点（最上层渲染）
        for (int i = count - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && search(child, depth + 1)) return true;
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node, String cleanText) {
        if (node.isPassword() || node.isEditable()) return false;

        // 1. 优先节点自身点击
        if (node.isClickable()) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "跳过成功：执行了节点自身 ACTION_CLICK");
                return true;
            }
        }

        // 2. 尝试可点击的祖先节点
        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < MAX_ANCESTOR_DEPTH) {
            if (parent.isPassword() || parent.isEditable()) return false;
            if (parent.isClickable()) {
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "跳过成功：执行了祖先节点 ACTION_CLICK (depth=" + depth + ")");
                    return true;
                }
                break;
            }
            parent = parent.getParent();
            depth++;
        }

        // 3. 屏幕物理坐标模拟手指点击（专治不可点击或穿山甲/优量汇 SDK）
        if (gestureTapIfButtonLike(node, cleanText)) {
            Log.i(TAG, "跳过成功：执行了屏幕坐标手势点击");
            return true;
        }
        return false;
    }

    /** 严格校验防误触的手势坐标点击，专为 2K 高分辨率屏适配尺寸 */
    private boolean gestureTapIfButtonLike(AccessibilityNodeInfo node, String cleanText) {
        if (node.isPassword() || node.isEditable()) return false;
        if (cleanText.length() > 16) return false;
        if (!SKIP_STRICT.matcher(cleanText).matches()) {
            Log.d(TAG, "手势点击未通过严格文案校验: " + cleanText);
            return false;
        }

        Rect r = new Rect();
        node.getBoundsInScreen(r);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        // 适配 2K / 1440x3200 屏幕，按钮高度放宽至屏幕 1/6 (约 530px)，宽度小于半屏
        if (r.width() <= 0 || r.height() <= 0 || r.height() > sh / 6) {
            Log.d(TAG, "手势尺寸校验不通过: " + r.toShortString());
            return false;
        }
        if (r.width() > sw / 2) return false;
        if (r.left < 0 || r.top < 0 || r.right > sw || r.bottom > sh) return false;

        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 30);
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
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }
}
