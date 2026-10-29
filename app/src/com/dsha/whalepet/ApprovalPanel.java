package com.dsha.whalepet;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;

/**
 * 审批面板：一条待确认命令的展示、允许/拒绝、30 秒未回应提醒。
 *
 * 从 PetService 拆出来的第三块。它只负责审批这件事；把内容交给 Host 去挂窗口、
 * 弹气泡、判断前台是不是 DSH。
 */
final class ApprovalPanel {

    interface Host {
        String str(int id);

        void bubble(String text);

        void hidePanel();

        /** 把内容挂成悬浮面板（服务负责宽度与摆放）。 */
        void present(View content);

        /** 桌宠窗口是否还在（不在就不弹）。 */
        boolean petReady();

        /** 用户当前是否就在 DSH / 本 App 里（在里面就不打扰）。 */
        boolean foregroundInDsh();
    }

    private final Context ctx;
    private final Handler main;
    private final ExecutorService io;
    private final Host host;

    private String id = "";
    private Bridge.Approval pending;
    private long at;
    private long remindedAt;

    ApprovalPanel(Context ctx, Handler main, ExecutorService io, Host host) {
        this.ctx = ctx;
        this.main = main;
        this.io = io;
        this.host = host;
    }

    /**
     * 收到一条待审批：同一条不重复弹。
     *
     * @return true 表示这是新的一条、已经弹了
     */
    boolean handlePending(Bridge.Approval a) {
        if (a == null || a.id == null) return false;
        if (a.id.equals(id)) return false;
        id = a.id;
        show(a);
        return true;
    }

    Bridge.Approval pending() {
        return pending;
    }

    private void show(Bridge.Approval a) {
        if (!host.petReady()) return;
        at = System.currentTimeMillis();
        remindedAt = 0L;
        pending = a;
        present(a);
    }

    /** 面板被别的面板顶掉后，重新把同一条审批弹回来（不重置提醒计时）。 */
    void reshow() {
        if (pending == null) return;
        Bridge.diag("appr", "reshow " + pending.id);
        present(pending);
    }

    private void present(Bridge.Approval a) {
        if (!host.petReady()) return;
        Bridge.diag("appr", "show " + a.id + " " + (a.toolName == null ? "" : a.toolName));
        host.hidePanel();

        LinearLayout box = PanelUi.card(ctx, host.str(R.string.pet_approval_title), true);
        StringBuilder sb = new StringBuilder();
        if (a.toolName != null && !a.toolName.isEmpty()) sb.append(a.toolName);
        if (a.reason != null && !a.reason.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(a.reason);
        }
        if (sb.length() == 0) sb.append(host.str(R.string.pet_approval_no_desc));
        TextView desc = PanelUi.body(ctx, box, sb.toString(), 12.5f, Color.WHITE);
        desc.setMaxLines(8);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.dp(ctx, 10);
        box.addView(row, rowLp);

        TextView allow = PanelUi.smallButton(ctx, host.str(R.string.pet_btn_allow_once), true);
        allow.setOnClickListener(v -> decide(a, true));
        row.addView(allow, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView deny = PanelUi.smallButton(ctx, host.str(R.string.pet_btn_deny), false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f);
        dlp.leftMargin = Ui.dp(ctx, 8);
        deny.setOnClickListener(v -> decide(a, false));
        row.addView(deny, dlp);

        host.present(box);
        host.bubble(host.str(R.string.pet_bubble_approval_pending));
    }

    private void decide(final Bridge.Approval a, final boolean allow) {
        Bridge.diag("appr", "tap " + a.id + " " + (allow ? "allow" : "deny"));
        at = 0L;
        pending = null;
        host.hidePanel();
        host.bubble(host.str(allow ? R.string.pet_bubble_allowed : R.string.pet_bubble_denied));
        io.execute(() -> {
            boolean ok = Bridge.decide(a.id, allow, 2000);
            Bridge.diag("appr", "post " + a.id + " ok=" + ok);
            if (!ok) main.post(() -> host.bubble(host.str(R.string.pet_bubble_approval_failed)));
        });
    }

    /** 每 5 秒由服务调用：30 秒没回应且用户不在 DSH 里就再提醒一次。 */
    void tick() {
        if (at <= 0 || pending == null) return;
        long now = System.currentTimeMillis();
        if (now - at <= 30000L || now - remindedAt <= 30000L) return;
        if (host.foregroundInDsh()) return;     // 正在看 DSH，不需要打扰
        remindedAt = now;
        host.bubble(host.str(R.string.pet_bubble_approval_remind));
        show(pending);
        Bridge.diag("approval", "reminded after 30s");
    }
}
