package com.dsha.whalepet;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍服务：只做一件事——判断"当前前台是不是游戏、是不是全屏"。
 *
 * 它不读屏幕内容、不点按、不注入，只用窗口事件里的包名和窗口边界。
 * 关闭这个开关就完全不生效（服务开着也什么都不做）。
 */
public class PetAccessibility extends AccessibilityService {

    /** 跨进程内共享给 PetService 的状态。 */
    static volatile String pkg = "";
    static volatile boolean fullscreen;
    static volatile boolean landscape;
    static volatile boolean connected;
    /** 服务注册的回调：前台/全屏状态一变就调它（事件驱动，避免 3 秒巡检延迟）。 */
    static volatile Runnable onChange;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        instance = this;
        Bridge.diag("a11y", "connected");
    }

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        super.onDestroy();
        Bridge.diag("a11y", "destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String p = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (p.isEmpty()) return;
        if (!p.equals(pkg)) {
            pkg = p;
            // 不记录包名：那是用户的 App 使用清单，诊断日志不该带
        }
        fullscreen = computeFullscreen();
        try {
            landscape = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) { Log.ignored("PetAccessibility", t);
            landscape = false;
        }
        Runnable cb = onChange;
        if (cb != null) {
            long now2 = System.currentTimeMillis();
            if (now2 - lastNotify > 250L) {   // 250ms 去抖，避免窗口狂刷
                lastNotify = now2;
                try { cb.run(); } catch (Throwable t) { Log.ignored("PetAccessibility", t); /* 忽略 */ }
            }
        }
    }

    private static long lastNotify;

    @Override
    public void onInterrupt() {
        // 不需要
    }

    /**
     * 当前窗口是否铺满屏幕。
     *
     * 不能只看根节点：游戏基本是 Surface 渲染，根节点常常为空或很小。
     * 这里遍历无障碍窗口列表，取"活动/聚焦"的那个窗口的边界来判断。
     */
    private boolean computeFullscreen() {
        try {
            // 桌面/系统界面/输入法/自己：即使铺满屏幕也不算"全屏应用"，
            // 否则桌宠在桌面就会被收走。
            String p = pkg == null ? "" : pkg;
            if (p.isEmpty() || p.equals("android") || p.contains("systemui")
                    || p.contains("launcher") || p.contains("input")
                    || p.equals(getPackageName())) {
                return false;
            }
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int sw = dm.widthPixels, sh = dm.heightPixels;
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                Rect r = new Rect();
                for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                    if (w == null) continue;
                    // 不再只认"活动/聚焦"窗口：全屏视频的播放窗口可能两者都不是
                    w.getBoundsInScreen(r);
                    if (r.width() >= sw * 0.95f && r.height() >= sh * 0.90f) return true;
                }
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                Rect r = new Rect();
                root.getBoundsInScreen(r);
                if (r.width() >= sw * 0.90f && r.height() >= sh * 0.90f) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 给 PetService 用的查询 ----------------

    public static boolean isConnected() {
        return connected;
    }

    /** 当前前台是不是游戏。 */
    public static boolean foregroundIsGame(Context c) {
        String p = pkg;
        if (p == null || p.isEmpty()) return false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(p, 0);
            if (ai.category == ApplicationInfo.CATEGORY_GAME
                    || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return true;
        } catch (Throwable t) { Log.ignored("PetAccessibility", t);
            // 查不到就靠下面
        }
        // 只认系统元数据；不按包名猜（渠道包没有分类时，靠"窗口铺满屏幕"判断）
        return false;
    }

    public static boolean isFullscreen() {
        return fullscreen;
    }

    /** 当前是否横屏。 */
    public static boolean isLandscape() {
        return landscape;
    }

    /**
     * 当前是否有媒体在播放（系统级信号，不认任何具体应用）。
     * 全屏视频在窗口边界拿不到时，这是最可靠的通用依据。
     */
    public static boolean isMediaActive() {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    getInstance().getSystemService(android.content.Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile PetAccessibility instance;

    private static PetAccessibility getInstance() {
        return instance;
    }

    public static String foregroundPkg() {
        return pkg;
    }
}
