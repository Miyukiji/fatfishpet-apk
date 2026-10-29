package com.dsha.whalepet;

import android.content.Context;
import android.os.Handler;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/** 头顶气泡：一个不可触摸的悬浮窗，默认 4.5 秒后自己消失。 */
public final class BubbleWindow extends OverlayWindow {

    private static final long LIFE_MS = 4500L;

    private final Runnable autoHide = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    public BubbleWindow(Context c, WindowManager wm, Handler main) {
        super(c, wm, main);
    }

    /** 显示一句气泡；position 由 place(...) 负责（会跟着小人走）。 */
    public void show(String text, float petX, float petY, int petW, int screenW) {
        show(text, petX, petY, petW, screenW, LIFE_MS);
    }

    /** @param lifeMs <=0 表示不自动消失（需要用户回应时用），由调用方 hide()。 */
    public void show(String text, float petX, float petY, int petW, int screenW, long lifeMs) {
        if (text == null || text.isEmpty()) return;
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFF101828);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(Ui.card(ctx, 0xF2FFFFFF, 14f, 0x1A0F172A));
        t.setMaxWidth(dp(280));
        t.setLineSpacing(dp(2), 1f);

        if (view != null) remove("BubbleWindow");
        WindowManager.LayoutParams params = newLp(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        if (!add(t, params, "BubbleWindow")) return;
        place(petX, petY, petW, screenW);
        main.removeCallbacks(autoHide);
        if (lifeMs > 0) main.postDelayed(autoHide, lifeMs);
    }

    /** 跟随小人位置重新摆放（小人移动/换尺寸/旋转后调用）。 */
    public void place(float petX, float petY, int petW, int screenW) {
        if (view == null || lp == null) return;
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = Math.max(view.getMeasuredWidth(), dp(40));
        int bh = Math.max(view.getMeasuredHeight(), dp(28));
        int x = clamp(Math.round(petX + petW / 2f - bw / 2f), 0, Math.max(0, screenW - bw));
        int y = Math.round(petY) - bh - dp(4);
        if (y < 0) y = Math.round(petY) + dp(4);
        lp.x = x;
        lp.y = y;
        update("BubbleWindow");
    }

    public void hide() {
        main.removeCallbacks(autoHide);
        remove("BubbleWindow");
    }
}
