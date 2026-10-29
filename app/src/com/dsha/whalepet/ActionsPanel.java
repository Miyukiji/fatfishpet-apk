package com.dsha.whalepet;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「动作点播」面板：只列待机池里的动作，点一条播一次。
 *
 * 从 PetService 拆出来的第四块（面板装配）。它只负责建列表与回报点击，
 * 播放与窗口由 Host 处理。
 */
final class ActionsPanel {

    interface Host {
        String str(int id);

        /** 把内容挂成悬浮面板。 */
        void present(View content);

        void hidePanel();

        /** 用户点了某段动作。 */
        void playOnce(Anim.Act a);
    }

    private final Context ctx;
    private final Anim anim;
    private final Host host;

    ActionsPanel(Context ctx, Anim anim, Host host) {
        this.ctx = ctx;
        this.anim = anim;
        this.host = host;
    }

    void show() {
        LinearLayout box = PanelUi.card(ctx, host.str(R.string.pet_panel_actions_title), true);
        // 外层 PanelWindow 已经套了 ScrollView，这里不要再套一层（嵌套滚动会互相抢手势）
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);

        // 只列待机池，避免点播到工作/睡眠等状态动作
        List<Anim.Act> acts = new ArrayList<>(anim.idlePool);
        acts.remove(anim.idle);                 // 待机本身不列，避免点了没变化
        Collator coll = Collator.getInstance(Locale.CHINA);
        acts.sort((x, y) -> coll.compare(
                x.display == null ? x.dir : x.display, y.display == null ? y.dir : y.display));
        for (final Anim.Act a : acts) {
            TextView row = new TextView(ctx);
            row.setText(a.display == null || a.display.isEmpty() ? a.dir : a.display);
            row.setTextColor(0xFFD6E1F5);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            row.setPadding(Ui.dp(ctx, 2), Ui.dp(ctx, 8), Ui.dp(ctx, 2), Ui.dp(ctx, 8));
            row.setClickable(true);
            row.setOnClickListener(v -> {
                host.hidePanel();
                host.playOnce(a);
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(ctx, 4);
        box.addView(list, lp);
        host.present(box);
    }
}
