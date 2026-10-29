package com.dsha.whalepet;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;

/**
 * 信息面板窗口（余额 / 天气 / 动作点播 / 审批）。
 * 只负责"窗口怎么挂、挂哪、多高"，面板内容由调用方构建。
 */
public final class PanelWindow extends OverlayWindow {

    public PanelWindow(Context c, WindowManager wm, Handler main) {
        super(c, wm, main);
    }

    /** 显示内容（自动包一层 ScrollView：屏幕矮或条目多时在框里滚，而不是被裁掉）。 */
    public void show(View content, int width) {
        show(content, width, null);
    }

    /** @param onOutside 点击面板以外的位置时回调（用来收起面板） */
    public void show(View content, int width, final Runnable onOutside) {
        if (view != null) remove("PanelWindow");
        ScrollView sc = new ScrollView(ctx);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(false);
        sc.addView(content);
        if (onOutside != null) {
            sc.setOnTouchListener((v, e) -> {
                if (e.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
                    onOutside.run();
                    return true;
                }
                return false;
            });
        }
        WindowManager.LayoutParams params = newLp(width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        add(sc, params, "PanelWindow");
    }

    /** 摆到小人旁边（优先上方，放不下放下方），并把高度封顶。 */
    public void place(float petX, float petY, int petW, int petH,
                      int screenW, int screenH, int width) {
        if (view == null || lp == null) return;
        int cap = Math.max(dp(160), screenH - dp(24));
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
        int ph = Math.min(Math.max(view.getMeasuredHeight(), dp(64)), cap);
        if (lp.height != ph) lp.height = ph;
        int gap = dp(6);
        int above = Math.round(petY) - ph - gap;
        int below = Math.round(petY) + petH + gap;
        int y = above >= 0 ? above : below;
        if (y + ph > screenH) y = Math.max(0, screenH - ph);
        int x = clamp(Math.round(petX + petW / 2f - width / 2f), 0, Math.max(0, screenW - width));
        lp.x = x;
        lp.y = y;
        update("PanelWindow");
    }

    public void hide() {
        remove("PanelWindow");
    }
}
