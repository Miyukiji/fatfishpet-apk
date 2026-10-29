package com.dsha.whalepet;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 面板里反复出现的几块：卡片、正文、分割线、小按钮、宽度。
 *
 * 提取原因：PetService 与 WalletPanel 各写了一份 card/body/thinLine，样式已经开始漂移
 * （10sp vs 10.5sp、padding 13/11 vs 14/12、背景一个是 panel_bg 一个是 Ui.card）。
 */
final class PanelUi {

    private PanelUi() { }

    /** @param light true = 菜单/审批用的浅色 panel_bg；false = 钱包的深色半透明卡片 */
    static LinearLayout card(Context c, String title, boolean light) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        if (light) {
            box.setBackgroundResource(R.drawable.panel_bg);
            box.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 12));
        } else {
            box.setBackground(Ui.card(c, 0xF2141B2A, 16f, 0x33FFFFFF));
            box.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        }
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Ui.LABEL);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, light ? 10f : 10.5f);
        t.setLetterSpacing(0.08f);
        box.addView(t);
        return box;
    }

    static TextView body(Context c, LinearLayout parent, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (sp >= 16f) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 4);
        parent.addView(t, lp);
        return t;
    }

    static void thinLine(Context c, LinearLayout parent) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f)));
        lp.topMargin = dp(c, 6);
        lp.bottomMargin = dp(c, 2);
        v.setBackgroundColor(0x22FFFFFF);
        parent.addView(v, lp);
    }

    static TextView smallButton(Context c, String text, boolean primary) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 8), dp(c, 9), dp(c, 8), dp(c, 9));
        t.setTextColor(primary ? Color.WHITE : 0xFFD6E1F5);
        t.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_ghost);
        t.setClickable(true);
        return t;
    }

    /** 面板宽度：小屏时按屏宽收窄，绝不让面板溢出去。 */
    static int panelWidth(Context c, int screenW, boolean actionsPanel) {
        int want = dp(c, actionsPanel ? 206 : 258);
        int max = Math.max(dp(c, 180), screenW - dp(c, 28));
        return Math.min(want, max);
    }

    private static int dp(Context c, float v) {
        return Ui.dp(c, v);
    }
}
