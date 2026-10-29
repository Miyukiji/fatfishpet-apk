package com.dsha.whalepet;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * 界面小工具：尺寸换算、圆角卡片、共用颜色。
 *
 * 这些原本在每个窗口类里各写一份（dp 有 6 份、卡片背景 2 份、小节标题色 4 处），
 * 改一次配色要挨个找，所以收到这里。
 */
final class Ui {

    /** 小节标题（"余额""账户"这类）的灰蓝字：0xFF8FA6C8。 */
    static final int LABEL = 0xFF8FA6C8;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * 圆角卡片背景。
     *
     * @param fill      填充色（含 alpha）
     * @param radiusDp  圆角半径（dp）
     * @param stroke    描边色；0 表示不描边，否则描 1dp
     */
    static GradientDrawable card(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) bg.setStroke(Math.max(1, dp(c, 1)), stroke);
        return bg;
    }
}
