package com.dsha.whalepet;

import android.content.Context;

/**
 * 静态文案门面：给没有 Context 的数据层（Weather / BalanceClient / Bridge）取本地化字符串。
 *
 * 单测里没有 Android 运行时，Txt.init 不会被调用，s() 直接返回调用方给的 fallback（中文），
 * 所以 JVM 单测仍然成立。
 */
final class Txt {

    private static volatile Context app;

    private Txt() { }

    static void init(Context c) {
        if (c == null) return;
        app = c.getApplicationContext();
    }

    static String s(int id, String fallback) {
        Context c = app;
        if (c == null) return fallback;
        try {
            return c.getString(id);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
