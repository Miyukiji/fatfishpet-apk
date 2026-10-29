package com.dsha.whalepet;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗的公共骨架：挂载、更新、移除、dp、clamp。
 *
 * 气泡和面板以前各抄一份，改一处漏一处；现在只在这里出现。
 */
abstract class OverlayWindow {

    protected final Context ctx;
    protected final WindowManager wm;
    protected final Handler main;

    protected View view;
    protected WindowManager.LayoutParams lp;

    OverlayWindow(Context c, WindowManager wm, Handler main) {
        this.ctx = c;
        this.wm = wm;
        this.main = main;
    }

    public boolean isShown() {
        return view != null;
    }

    /** 造一个左上对齐、半透明的悬浮窗参数。 */
    protected WindowManager.LayoutParams newLp(int width, int height, int flags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    /** @return 是否真的挂上了 */
    protected boolean add(View v, WindowManager.LayoutParams params, String tag) {
        try {
            wm.addView(v, params);
            view = v;
            lp = params;
            return true;
        } catch (Throwable err) {
            Log.ignored(tag + ".add", err);
            view = null;
            lp = null;
            return false;
        }
    }

    protected void update(String tag) {
        if (view == null || lp == null) return;
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable err) {
            Log.ignored(tag + ".update", err);   // 多半是窗口已被系统移除
        }
    }

    protected void remove(String tag) {
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (Throwable err) {
                Log.ignored(tag + ".remove", err);   // 已经不在窗口上
            }
        }
        view = null;
        lp = null;
    }

    protected int dp(float v) {
        return Ui.dp(ctx, v);
    }

    protected static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
