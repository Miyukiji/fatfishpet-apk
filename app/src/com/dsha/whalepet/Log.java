package com.dsha.whalepet;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 吞异常时至少留一条痕迹（按调用点限频 30 秒，最多每个点一条）。
 *
 * 刻意不直接依赖 Bridge：这个类要能在普通 JVM 单测里一起编译（Weather 也用它）。
 * Android 侧在 PetService.onCreate 里把 sink 接到 Bridge.diag。
 */
final class Log {

    interface Sink {
        void log(String where, String msg);
    }

    private static final ConcurrentHashMap<String, Long> LAST = new ConcurrentHashMap<>();
    private static final long WINDOW_MS = 30_000L;
    private static volatile Sink sink;

    private Log() { }

    static void setSink(Sink s) {
        sink = s;
    }

    static void ignored(String where, Throwable t) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(where);
        if (last != null && now - last < WINDOW_MS) return;
        LAST.put(where, now);
        Sink s = sink;
        if (s == null) return;                  // 单测环境：没人接就安静
        String msg = t == null ? "?" : t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
        s.log(where, msg);
    }
}
