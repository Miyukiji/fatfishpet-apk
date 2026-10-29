package com.dsha.whalepet;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 应用内功能说明。只有这一处说明入口（长按菜单里只做跳转，不另写一份）。 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0F1420);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        root.setPadding(pad, dp(34), pad, dp(30));

        title(root, getString(R.string.help_title));

        head(root, getString(R.string.help_head_gesture));
        sub(root, getString(R.string.help_body_gesture));

        head(root, getString(R.string.help_head_idle));
        sub(root, getString(R.string.help_body_idle));

        head(root, getString(R.string.help_head_temp_hide));
        sub(root, getString(R.string.help_body_temp_hide));

        head(root, getString(R.string.help_head_auto_hide));
        sub(root, getString(R.string.help_body_auto_hide));

        group(root, getString(R.string.help_group_dsh),
                getString(R.string.help_group_dsh_body), false);

        head(root, getString(R.string.help_head_credits));
        sub(root, getString(R.string.help_body_credits));

        scroll.addView(root);
        setContentView(scroll);
    }

    private void title(LinearLayout root, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(t);
    }

    private void sub(LinearLayout root, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFB6C4DC);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setLineSpacing(dp(3), 1f);
        t.setPadding(0, dp(5), 0, dp(10));
        // 素材许可以里带上游链接：让它们可点，方便核对署名
        android.text.util.Linkify.addLinks(t, android.text.util.Linkify.WEB_URLS);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        root.addView(t);
    }

    private void head(LinearLayout root, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.LABEL);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(16), 0, dp(6));
        root.addView(t);
    }

    /** 可折叠分组：点标题展开/收起正文。 */
    private void group(LinearLayout root, String title, String body, boolean open) {
        final TextView head = new TextView(this);
        head.setText((open ? "▾ " : "▸ ") + title);
        head.setTextColor(Ui.LABEL);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        head.setLetterSpacing(0.04f);
        head.setPadding(0, dp(14), 0, dp(6));
        head.setClickable(true);

        final TextView text = new TextView(this);
        text.setText(body);
        text.setTextColor(0xFFB6C4DC);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        text.setLineSpacing(dp(3), 1f);
        text.setPadding(0, dp(2), 0, dp(8));
        text.setVisibility(open ? View.VISIBLE : View.GONE);

        head.setOnClickListener(v -> {
            boolean show = text.getVisibility() != View.VISIBLE;
            text.setVisibility(show ? View.VISIBLE : View.GONE);
            head.setText((show ? "▾ " : "▸ ") + title);
        });
        root.addView(head);
        root.addView(text);
    }


    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
