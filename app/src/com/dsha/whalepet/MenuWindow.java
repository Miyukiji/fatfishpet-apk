package com.dsha.whalepet;

import android.content.Context;
import android.os.Handler;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.Arrays;
import java.util.Locale;

/**
 * 长按菜单窗口：条目、排序、摆放都在这里。
 *
 * 从 PetService 里搬出来的第一块（原来 1900 行什么都管）。菜单只负责"显示和回报点了哪个"，
 * 具体动作仍由 PetService 的 onMenu() 执行。
 */
final class MenuWindow extends OverlayWindow {

    interface Listener {
        void onPick(String id);
    }

    private final String title;
    private LinearLayout box;

    MenuWindow(Context c, WindowManager wm, Handler main, String title) {
        super(c, wm, main);
        this.title = title;
    }

    /** @param items {label, id}；"关闭菜单"永远排最后，其余按拼音排序 */
    void show(String[][] items, Listener l, float petX, float petY,
              int petH, int screenW, int screenH) {
        String closeLabel = null;
        for (String[] it : items) {
            if ("close".equals(it[1])) closeLabel = it[0];
        }
        final String last = closeLabel;
        if (last != null) {
            Collator coll = Collator.getInstance(Locale.CHINA);
            Arrays.sort(items, (x, y) -> {
                boolean cx = last.equals(x[0]);
                boolean cy = last.equals(y[0]);
                if (cx != cy) return cx ? 1 : -1;
                return coll.compare(x[0], y[0]);
            });
        }

        box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(dp(13), dp(11), dp(13), dp(12));

        TextView head = new TextView(ctx);
        head.setText(title);
        head.setTextColor(Ui.LABEL);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        head.setLetterSpacing(0.08f);
        box.addView(head);

        for (String[] it : items) {
            TextView row = new TextView(ctx);
            row.setText(it[0]);
            row.setTextColor(0xFFE6EEFB);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            row.setSingleLine(true);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setClickable(true);
            final String action = it[1];
            row.setOnClickListener(v -> l.onPick(action));
            box.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        if (view != null) remove("MenuWindow");
        ScrollView sc = new ScrollView(ctx);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(box);
        WindowManager.LayoutParams params = newLp(width(screenW),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        if (!add(sc, params, "MenuWindow")) return;
        place(petX, petY, petH, screenW, screenH);
    }

    void place(float petX, float petY, int petH, int screenW, int screenH) {
        if (view == null || lp == null) return;
        int mw = width(screenW);
        // 高度封顶：菜单比屏幕高时不能把最后几项裁掉
        int maxH = Math.min(dp(330), Math.max(dp(120), screenH - dp(24)));
        view.measure(View.MeasureSpec.makeMeasureSpec(mw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int mh = Math.max(view.getMeasuredHeight(), dp(80));
        int gap = dp(6);
        int above = Math.round(petY) - mh - gap;
        int y = above >= 0 ? above : Math.round(petY) + petH + gap;
        if (y + mh > screenH) y = Math.max(0, screenH - mh);
        lp.x = clamp(Math.round(petX), 0, Math.max(0, screenW - mw));
        lp.y = y;
        update("MenuWindow");
    }

    void hide() {
        remove("MenuWindow");
    }

    private int width(int screenW) {
        int max = Math.max(dp(132), screenW - dp(28));
        return Math.min(dp(158), max);
    }
}
