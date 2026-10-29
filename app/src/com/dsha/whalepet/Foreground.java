package com.dsha.whalepet;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

/**
 * 前台应用是谁 —— 用系统的「使用情况访问」权限查（PACKAGE_USAGE_STATS）。
 *
 * 为什么要它：只看"系统栏有没有被藏起来"没法区分"全屏看图"和"全屏游戏/视频"，
 * 结果打开图片查看器桌宠也被收走（真机反馈）。要知道前台包名，比无障碍更轻的办法
 * 就是这个权限：用户在设置里授权一次，之后只读应用切换事件，不读内容、不注入。
 *
 * 没授权时 {@link #current} 返回 null，调用方按"不收起"处理 —— 宁可桌宠赖着，也不要误收。
 */
public final class Foreground {

    /** 只回看最近这么久的应用切换事件，足够定位"现在是谁在前台"。 */
    private static final long WINDOW_MS = 120_000L;   // 太窄会让"停留超过 10 秒"查不到前台事件

    private Foreground() {
    }

    /** 是否已授权「使用情况访问」。 */
    public static boolean hasPermission(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前前台应用的包名；拿不到（没授权/没事件）返回 null。 */
    public static String current(Context c) {
        if (!hasPermission(c)) return null;
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - WINDOW_MS, now);
            if (ev == null) return null;
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    last = e.getPackageName();
                }
            }
            if (last != null) return last;
            // 兜底：用近 1 天的使用统计里"最后使用时间"最新的应用
            java.util.List<android.app.usage.UsageStats> stats =
                    usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                            now - 24L * 3600 * 1000, now);
            if (stats == null) return null;
            long best = 0L;
            for (android.app.usage.UsageStats u : stats) {
                if (u != null && u.getLastTimeUsed() > best) {
                    best = u.getLastTimeUsed();
                    last = u.getPackageName();
                }
            }
            return last;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * sinceMs 之后，这个应用有没有被"切走"（ACTIVITY_PAUSED）。
     * 这是判断"离开当前应用"最可靠的事件：切到桌面、切到别的 App 都会 pause。
     */
    public static boolean pausedSince(Context c, String pkg, long sinceMs) {
        if (pkg == null || pkg.isEmpty() || !hasPermission(c)) return false;
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return false;
            UsageEvents ev = usm.queryEvents(sinceMs, System.currentTimeMillis());
            if (ev == null) return false;
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int type = e.getEventType();
                boolean paused = type == UsageEvents.Event.ACTIVITY_PAUSED || type == 2;
                if (paused && pkg.equals(e.getPackageName())) return true;
            }
        } catch (Throwable t) { Log.ignored("Foreground", t);
            // 查不到就靠超时兜底
        }
        return false;
    }







}
