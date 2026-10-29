package com.dsha.whalepet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.widget.LinearLayout;
import android.widget.TextView;


/**
 * 双击钱包面板：余额 / 本机（今日·累计）/ 账户今日 / 账户累计 + 分时 + 天气。
 *
 * 只负责"这一块 UI 与取数"，窗口怎么挂由 PanelWindow 管，服务只调 build()。
 * 拿不到的金额一律显示 --，绝不估算。
 */
public final class WalletPanel {

    private final Context ctx;
    private final Handler main;
    private final java.util.concurrent.ExecutorService io;
    private final Runnable onHeightChanged;

    private LinearLayout box;
    private TextView bal, local, acctToday, wNow, wTomorrow;
    /** 账户会话里拿到的官方余额（没有 API Key 时的兜底）。 */
    private double acctBalance = Double.NaN;
    /** 面板已被收起：取数回来就别再写控件。 */
    private volatile boolean disposed;

    /** 由 PetService 在收起面板时调用。 */
    public void dispose() {
        disposed = true;
    }

    public WalletPanel(Context ctx, Handler main, java.util.concurrent.ExecutorService io,
                       Runnable onHeightChanged) {
        this.ctx = ctx;
        this.main = main;
        this.io = io;
        this.onHeightChanged = onHeightChanged;
    }

    /** 组装面板并立刻开始取数。 */
    public LinearLayout build() {
        box = card(ctx.getString(R.string.wallet_title));
        bal = body(ctx.getString(R.string.wallet_balance_na), 16f, Color.WHITE);
        local = body(cachedLocal(), 14f, 0xFFD6E1F5);
        acctToday = body(cachedAcct(), 14f, 0xFFD6E1F5);

        thinLine();
        wNow = body(ctx.getString(R.string.wallet_weather_loading), 13f, Color.WHITE);
        wTomorrow = body("", 11.5f, 0xFFD6E1F5);

        fetchUsage();
        fetchAccount();
        fetchWeather();
        return box;
    }

    // ---------------- 取数 ----------------

    /** 上次成功取到的账户行（先显示，避免首帧空白）。 */
    private String cachedAcct() {
        String l = AccountSession.cache(ctx);
        long at = prefs().getLong("acctAt", 0L);
        return l.isEmpty() || !l.startsWith(ctx.getString(R.string.wallet_acct_prefix))
                ? ctx.getString(R.string.wallet_acct_na) : l;
    }

    /** 上次成功取到的本机行（先显示，避免首帧空白）。 */
    private String cachedLocal() {
        String l = prefs().getString("localLine", "");
        long at = prefs().getLong("localAt", 0L);
        return l.isEmpty() || System.currentTimeMillis() - at > 10 * 60_000L
                ? ctx.getString(R.string.wallet_local_na) : l;
    }

    /** 面板关闭/服务销毁后 io 池可能已关：在途回调再提交任务不要炸主线程。 */
    private void ioRun(Runnable r) {
        if (disposed) return;
        try {
            io.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.ignored("WalletPanel.io", e);
        }
    }
    private void fetchUsage() {
        fetchUsage(1);
    }

    private void fetchUsage(final int tries) {
        final String key = AccountSession.apiKey(ctx);
        // 余额单独一条线程、拿到就更新，与桥并行。不再 join 等它：Http 的连接、读取各有一个
        // 6s 超时，最坏要 12s，之前 6.5s 就判"超时"，会把慢网下已经成功的结果丢掉。
        Thread tb = new Thread(() -> {
            final BalanceClient.Result b = BalanceClient.fetch(key, 6000);
            main.post(() -> {
                if (disposed) return;
                String line = BalanceFmt.statusLine(b);
                if (key.isEmpty()) {
                    // 没填 Key 但账户登录过：用账户 summary 里的余额兜底，别一直显示"还没填 Key"
                    double acct = accountBalance();
                    if (!Double.isNaN(acct)) line = "¥" + Bridge.fmt(acct);
                }
                bal.setText(ctx.getString(R.string.wallet_balance_prefix) + line);
                if (onHeightChanged != null) onHeightChanged.run();
            });
        }, "wal-bal");
        tb.setDaemon(true);
        tb.start();
        ioRun(() -> {
            final Bridge.Usage u = Bridge.usage(4000);
            main.post(() -> {
                if (disposed) return;
                if (!u.ok) {
                    if (tries < 3) {
                        final long wait = 1000L * tries;
                        ioRun(() -> {
                            try {
                                Thread.sleep(wait);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            fetchUsage(tries + 1);
                        });
                    } else {
                        Bridge.diag("wallet", "usage failed: " + u.error
                                + " base=" + Bridge.baseUrl() + " at=" + System.currentTimeMillis());
                        local.setText(ctx.getString(R.string.wallet_local_na_nobridge));
                    }
                    return;
                }
                String today = Double.isNaN(u.todayCost) ? "¥--" : "¥" + Bridge.fmt(u.todayCost);
                double total = !Double.isNaN(u.lifetimeCost) ? u.lifetimeCost : u.windowCost;
                String all = Double.isNaN(total) ? "¥--" : "¥" + Bridge.fmt(total);
                String label = Double.isNaN(u.lifetimeCost)
                        ? ctx.getString(R.string.wallet_local_label_recent)
                        : ctx.getString(R.string.wallet_local_label_total);
                String line = ctx.getString(R.string.wallet_local_line, today, label, all);
                local.setText(line);
                Bridge.diag("wallet", "local " + line);
                prefs().edit().putString("localLine", line)
                        .putLong("localAt", System.currentTimeMillis()).apply();
                if (!disposed) {
                    ioRun(() -> {
                        try {
                            Thread.sleep(8000L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        if (!disposed) fetchUsage(1);
                    });
                }
                if (onHeightChanged != null) onHeightChanged.run();
            });
        });
    }

    private static final long ACCT_TTL_MS = 5 * 60_000L;

    /** 上次从账户 summary 拿到的余额（没 Key 时用来兜底）。 */
    private double accountBalance() {
        String v = prefs().getString("acctBal", "");
        if (v.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (Throwable err) {
            return Double.NaN;
        }
    }

    private void fetchAccount() {
        fetchAccount(1);
    }

    private void fetchAccount(final int tries) {
        final String token = AccountSession.token(ctx);
        final String cookie = AccountSession.cookie(ctx);
        if (token.isEmpty() && cookie.isEmpty()) return;
        long at = prefs().getLong("acctAt", 0L);
        String cached = AccountSession.cache(ctx);
        if (System.currentTimeMillis() - at < ACCT_TTL_MS
                && cached.startsWith(ctx.getString(R.string.wallet_acct_prefix))) {
            acctToday.setText(cached);        // 5 分钟内不重复请求
            double acctBal = accountBalance();
            if (!Double.isNaN(acctBal) && bal.getText().toString().contains("--")) {
                bal.setText(ctx.getString(R.string.wallet_balance_money, Bridge.fmt(acctBal)));
            }
            return;
        }
        ioRun(() -> {
            final int devOff = java.util.TimeZone.getDefault()
                    .getOffset(System.currentTimeMillis()) / 1000;
            final AccountApi.Spend sp = AccountSession.pageFresh(ctx, 30 * 60_000L)
                    ? AccountApi.fromPage(AccountSession.pageSummary(ctx), AccountSession.pageCost(ctx), devOff)
                    : AccountApi.fetch(token, cookie, AccountSession.deviceHeaders(ctx));
            main.post(() -> {
                if (acctToday.getWindowToken() == null) return;
                if (sp.invalidToken) {
                    AccountSession.clear(ctx);
                    prefs().edit().remove("acctBal").putLong("acctAt", 0L).apply();
                    acctToday.setText(ctx.getString(R.string.wallet_acct_na_login));
                    Bridge.diag("acct", "token invalid, cleared session");
                    return;
                }
                int pageTz = AccountApi.tzOf(AccountSession.usageUrl(ctx));
                double pageToday = AccountApi.todayFromPage(AccountSession.usageBody(ctx), pageTz);
                double todayV = !Double.isNaN(pageToday) && System.currentTimeMillis() - AccountSession.usageAt(ctx) < 15 * 60_000L
                        ? pageToday : sp.today;
                String todayTxt = money(todayV);
                // 累计只来自 summary；拿不到时退回本月，并如实标「本月」
                String totalTxt = Double.isNaN(sp.total) && !Double.isNaN(sp.month)
                        ? ctx.getString(R.string.wallet_total_month, money(sp.month))
                        : ctx.getString(R.string.wallet_total_all, money(sp.total));
                acctBalance = sp.balance;
                if (!Double.isNaN(sp.balance) && (bal.getText().toString().contains("--"))) {
                    bal.setText(ctx.getString(R.string.wallet_balance_money, Bridge.fmt(sp.balance)));
                }
                String line = ctx.getString(R.string.wallet_acct_line, todayTxt, totalTxt);
                acctToday.setText(line);
                Bridge.diag("acct", "show today=" + todayTxt + " total=" + totalTxt
                        + " pageToday=" + pageToday + " spToday=" + sp.today
                        + " spTotal=" + sp.total + " balance=" + sp.balance);
                // 一个数都没拿到就不缓存，否则接下来 5 分钟都不会重试
                if (Double.isNaN(todayV) && Double.isNaN(sp.total) && tries < 2) {
                    ioRun(() -> {
                        try {
                            Thread.sleep(1500L);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        fetchAccount(tries + 1);
                    });
                    return;
                }
                if (!Double.isNaN(todayV) || !Double.isNaN(sp.total) || !Double.isNaN(sp.month)) {
                    AccountSession.saveCache(ctx, line);
                    android.content.SharedPreferences.Editor e =
                            prefs().edit().putLong("acctAt", System.currentTimeMillis());
                    if (!Double.isNaN(sp.balance)) e.putString("acctBal", Double.toString(sp.balance));
                    e.apply();
                }
            });
        });
    }

    private static final long WEATHER_TTL_MS = 10 * 60_000L;

    private void fetchWeather() {
        long at = prefs().getLong("wAt", 0L);
        String cached = prefs().getString("wLine", "");
        String failText = ctx.getString(R.string.wallet_weather_fail);
        if (cached.startsWith(failText)) cached = "";   // 旧版本把失败写进了缓存，别再当有效数据
        boolean fresh = System.currentTimeMillis() - at < WEATHER_TTL_MS;
        if (cached.contains("\n")) {
            String[] parts = cached.split("\n", 2);
            wNow.setText(parts[0]);          // 有缓存就先显示，别让面板空着
            wTomorrow.setText(parts[1]);
            if (fresh) return;               // 10 分钟内不重复请求
        }
        ioRun(() -> {
            String[] out = {ctx.getString(R.string.wallet_weather_fail), ""};
            try {
                // 有定位权限就按坐标查 Open-Meteo；否则退回 wttr.in 的 IP 定位
                String[] openMeteo = null;
                try {
                    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        android.location.LocationManager lm =
                                (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                        android.location.Location loc = null;
                        if (lm != null) {
                            for (String pv : new String[]{android.location.LocationManager.NETWORK_PROVIDER,
                                    android.location.LocationManager.GPS_PROVIDER}) {
                                try {
                                    loc = lm.getLastKnownLocation(pv);
                                    if (loc != null) break;
                                } catch (Throwable t) { Log.ignored("WalletPanel", t);
                                    // 换下一个
                                }
                            }
                        }
                        if (loc != null) {
                            String u2 = "https://api.open-meteo.com/v1/forecast?latitude=" + loc.getLatitude()
                                    + "&longitude=" + loc.getLongitude()
                                    + "&current=temperature_2m,weather_code"
                                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                                    + "&timezone=auto&forecast_days=2";
                            String body2 = Http.get(u2, new String[]{"Accept", "application/json"}, 8000);
                            openMeteo = Weather.parseOpenMeteo(body2, "");
                        }
                    }
                } catch (Throwable t) { Log.ignored("WalletPanel", t);
                    // 退回 IP
                }
                if (openMeteo != null) {
                    final String[] f2 = openMeteo;
                    main.post(() -> {
                        if (disposed) return;
                        wNow.setText(f2[0]);
                        wTomorrow.setText(f2[1]);
                        if (!f2[0].equals(failText)) {
                            prefs().edit().putString("wLine", f2[0] + "\n" + f2[1])
                                    .putLong("wAt", System.currentTimeMillis()).apply();
                        }
                        Bridge.diag("weather", "open-meteo ok=" + !f2[0].equals(failText));
                    });
                    return;
                }
                String url = "https://wttr.in/?format=j1&m&lang=zh";
                String body = Http.get(url, new String[]{"Accept", "application/json",
                        "User-Agent", "curl/8"}, 8000);
                out = Weather.parse(body);
            } catch (Throwable t) { Log.ignored("WalletPanel", t);
                // 保持兜底文案
            }
            final String[] f = out;
            main.post(() -> {
                if (disposed) return;
                wNow.setText(f[0]);
                wTomorrow.setText(f[1]);
                boolean ok = !f[0].equals(failText);
                if (ok) {
                    prefs().edit().putString("wLine", f[0] + "\n" + f[1])
                            .putLong("wAt", System.currentTimeMillis()).apply();
                } else {
                    // 失败不入缓存：下次打开面板立刻重试，而不是等 10 分钟
                    prefs().edit().remove("wLine").putLong("wAt", 0L).apply();
                }
                Bridge.diag("weather", "wttr ok=" + ok + " line=" + f[0]);
                if (onHeightChanged != null) onHeightChanged.run();
            });
        });
    }

    private String money(double v) {
        return Double.isNaN(v) ? "--" : "¥" + Bridge.fmt(v);
    }

    // ---------------- UI 小工具 ----------------

    private SharedPreferences prefs() {
        return Prefs.pet(ctx);
    }

    private LinearLayout card(String title) {
        return PanelUi.card(ctx, title, false);
    }

    private TextView body(String text, float sp, int color) {
        return PanelUi.body(ctx, box, text, sp, color);
    }

    private void thinLine() {
        PanelUi.thinLine(ctx, box);
    }

    private int dp(float v) {
        return Ui.dp(ctx, v);
    }
}
