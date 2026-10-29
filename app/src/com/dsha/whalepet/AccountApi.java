package com.dsha.whalepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 平台私有接口（userToken 鉴权；API Key 无效）。
 *
 * 端点与口径（实测 2026-10）：
 *   余额/累计  /api/v0/users/get_user_summary   → biz_data.normal_wallets / bonus_wallets / total_costs
 *   每日花费   /api/v0/usage/by_api_key/cost    → biz_data.data[币种].series[].buckets[{time,cost}]
 *   本月汇总   /api/v0/usage/cost?month&year
 *
 * "今天"按**设备时区**切（平台接受 tz=秒偏移）；桶里的 time 是秒级时间戳。
 * 拿不到的数字保持 NaN，面板显示 --，绝不估算。
 */
public final class AccountApi {

    private static final String BASE = "https://platform.deepseek.com";
    private static final ThreadLocal<String[]> DEVICE = new ThreadLocal<>();

    private AccountApi() {
    }

    public static final class Spend {
        public double balance = Double.NaN;
        public double today = Double.NaN;
        /** 账户累计（只来自 summary.total_costs）。 */
        public double total = Double.NaN;
        public double month = Double.NaN;
        public boolean ok;
        public boolean invalidToken;
    }

    public static Spend fetch(String token, String cookie) {
        return fetch(token, cookie, new String[0]);
    }

    public static Spend fetch(String token, String cookie, String[] deviceHeaders) {
        DEVICE.set(deviceHeaders);
        Spend out = new Spend();
        if ((token == null || token.isEmpty()) && (cookie == null || cookie.isEmpty())) return out;

        long nowMs = System.currentTimeMillis();
        int tzOff = TimeZone.getDefault().getOffset(nowMs) / 1000;

        Resp summaryR = getResp(token, cookie, "/api/v0/users/get_user_summary");
        if (summaryR.invalidAuth()) {
            out.invalidToken = true;
            Bridge.diag("acct", "auth rejected http=" + summaryR.http + " len=" + (summaryR.body == null ? 0 : summaryR.body.length()));
            return out;
        }
        readSummary(summaryR.body, out);

        // 今日：平台要求 30 天整窗口且对齐本地零点，逐种对齐方式试
        long midnight = nowMs / 1000L - Math.floorMod(nowMs / 1000L + tzOff, 86400L);
        long day = 86400L;
        long[][] windows = {
                {midnight - 30 * day, midnight + day},
                {midnight - 30 * day, midnight},
                {midnight - 29 * day, midnight + day},
                {midnight - 30 * day, nowMs / 1000L},
        };
        // 四种对齐方式并行发，取第一个有数的（串行最坏 36s，并行最坏 9s）
        final java.util.concurrent.atomic.AtomicBoolean invalid =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.List<java.util.concurrent.Future<String>> pending = new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(windows.length);
        try {
            for (final long[] w : windows) {
                pending.add(pool.submit(() -> {
                    Resp r = getResp(token, cookie, "/api/v0/usage/by_api_key/cost?start=" + w[0]
                            + "&end=" + w[1] + "&tz=" + tzOff);
                    if (r.invalidAuth()) {
                        invalid.set(true);
                        return null;
                    }
                    if (r.body == null || r.body.contains("INVALID_PARAM")) return null;
                    double v = sumBucketsTz(r.body, tzOff);
                    return Double.isNaN(v) ? null : r.body;
                }));
            }
            for (java.util.concurrent.Future<String> f : pending) {
                String body;
                try {
                    body = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Throwable t) {
                    continue;
                }
                if (body == null) continue;
                double v = sumBucketsTz(body, tzOff);
                if (!Double.isNaN(v)) {
                    out.today = v;
                    Bridge.diag("acct", "today parsed len=" + body.length());
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        if (invalid.get()) out.invalidToken = true;

        if (Double.isNaN(out.month)) {
            Calendar c = Calendar.getInstance();
            Resp m = getResp(token, cookie, "/api/v0/usage/cost?month="
                    + (c.get(Calendar.MONTH) + 1) + "&year=" + c.get(Calendar.YEAR));
            if (m.invalidAuth()) out.invalidToken = true;
            if (m.body != null && !m.body.contains("INVALID_PARAM")) {
                Bridge.diag("acct", "month parsed len=" + m.body.length());
                try {
                    double v = sumCost(new JSONObject(m.body));
                    if (!Double.isNaN(v)) out.month = v;
                } catch (Throwable t) { Log.ignored("AccountApi", t);
                    // 保留 NaN
                }
            }
        }
        return out;
    }

    // ---------------- 解析 ----------------

    private static void readSummary(String body, Spend out) {
        if (body == null) return;
        Bridge.diag("acct", "summary len=" + body.length());
        try {
            JSONObject data = new JSONObject(body).optJSONObject("data");
            JSONObject biz = data == null ? null : data.optJSONObject("biz_data");
            if (biz == null) return;

            double bal = 0;
            boolean anyBal = false;
            for (String k : new String[]{"normal_wallets", "bonus_wallets"}) {
                JSONArray arr = biz.optJSONArray(k);
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject w = arr.optJSONObject(i);
                    if (w == null || !"CNY".equals(w.optString("currency", ""))) continue;
                    double v = dbl(w.optString("balance", ""));
                    if (!Double.isNaN(v)) {
                        bal += v;
                        anyBal = true;
                    }
                }
            }
            if (anyBal) out.balance = bal;

            JSONArray costs = biz.optJSONArray("total_costs");
            if (costs != null) {
                for (int i = 0; i < costs.length(); i++) {
                    JSONObject c = costs.optJSONObject(i);
                    if (c == null || !"CNY".equals(c.optString("currency", ""))) continue;
                    double v = dbl(c.optString("amount", ""));
                    if (!Double.isNaN(v)) out.total = v;
                }
            }
            out.ok = anyBal || !Double.isNaN(out.total);
        } catch (Throwable t) { Log.ignored("AccountApi", t);
            Bridge.diag("acct", "summary parse fail len=" + (body == null ? 0 : body.length()));
        }
    }

    /** 页面自己抓到的响应；tz 与页面请求一致才用。 */
    /** 用页面自己拿到的响应组装（最准，避免自己拼请求头）。 */
    public static Spend fromPage(String summary, String cost, int tzOffsetSec) {
        Spend out = new Spend();
        if (summary != null && !summary.isEmpty()) readSummary(summary, out);
        if (cost != null && !cost.isEmpty()) {
            double v = sumBucketsTz(cost, tzOffsetSec);
            if (!Double.isNaN(v)) out.today = v;
        }
        return out;
    }

    public static double todayFromPage(String body, int tzOffsetSec) {
        if (body == null || body.length() < 50) return Double.NaN;
        return sumBucketsTz(body, tzOffsetSec);
    }

    /** 页面请求 URL 里的 tz（秒）；取不到按北京时间。 */
    public static int tzOf(String url) {
        try {
            int i = url.indexOf("tz=");
            if (i < 0) return 28800;
            int j = i + 3;
            int e = j;
            while (e < url.length() && (Character.isDigit(url.charAt(e)) || url.charAt(e) == '-')) e++;
            return Integer.parseInt(url.substring(j, e));
        } catch (Throwable t) {
            return 28800;
        }
    }

    /** 按 tz 把"今天"的 cost 加起来。 */
    static double sumBucketsTz(String json, int tzOffsetSec) {
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null || data.optInt("biz_code", -1) != 0) return Double.NaN;
            JSONObject biz = data.optJSONObject("biz_data");
            JSONArray cur = biz == null ? null : biz.optJSONArray("data");
            if (cur == null) return Double.NaN;

            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
            f.setTimeZone(TimeZone.getTimeZone("GMT" + offset(tzOffsetSec)));
            String today = f.format(new Date());

            double sum = 0;
            boolean any = false;
            for (int i = 0; i < cur.length(); i++) {
                JSONObject c = cur.optJSONObject(i);
                if (c == null || !"CNY".equals(c.optString("currency", ""))) continue;
                JSONArray series = c.optJSONArray("series");
                if (series == null) continue;
                for (int j = 0; j < series.length(); j++) {
                    JSONObject se = series.optJSONObject(j);
                    JSONArray buckets = se == null ? null : se.optJSONArray("buckets");
                    if (buckets == null) continue;
                    for (int k = 0; k < buckets.length(); k++) {
                        JSONObject b = buckets.optJSONObject(k);
                        if (b == null) continue;
                        String d = f.format(new Date(b.optLong("time", 0L) * 1000L));
                        if (!today.equals(d)) continue;
                        double v = dbl(b.optString("cost", ""));
                        if (!Double.isNaN(v)) {
                            sum += v;
                            any = true;
                        }
                    }
                }
            }
            return any ? sum : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** 任意数组里的 cost 求和（本月接口）。 */
    private static double sumCost(Object node) {
        JSONArray arr = findArray(node);
        if (arr == null) return Double.NaN;
        double sum = 0;
        boolean any = false;
        for (int i = 0; i < arr.length(); i++) {
            double v = num(arr.opt(i), "cost");
            if (!Double.isNaN(v)) {
                sum += v;
                any = true;
            }
        }
        return any ? sum : Double.NaN;
    }

    private static double num(Object node, String key) {
        String norm = key.toLowerCase().replace("_", "");
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if (v instanceof Number && k.toLowerCase().replace("_", "").contains(norm)) {
                    return ((Number) v).doubleValue();
                }
                double r = num(v, key);
                if (!Double.isNaN(r)) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                double r = num(a.opt(i), key);
                if (!Double.isNaN(r)) return r;
            }
        }
        return Double.NaN;
    }

    private static JSONArray findArray(Object node) {
        if (node instanceof JSONArray) return (JSONArray) node;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                JSONArray a = findArray(o.opt(it.next()));
                if (a != null && a.length() > 0) return a;
            }
        }
        return null;
    }

    // ---------------- 请求 ----------------

    static final class Resp {
        final int http;
        final String body;

        Resp(int http, String body) {
            this.http = http;
            this.body = body;
        }

        /** 401，或 JSON 里 code=40002/40003，都算登录态失效（403 可能是 WAF）。 */
        boolean invalidAuth() {
            if (http == 401) return true;
            if (body == null) return false;
            try {
                int code = new JSONObject(body).optInt("code", 0);
                return code == 40002 || code == 40003;
            } catch (Throwable t) { Log.ignored("AccountApi", t);
                return false;
            }
        }
    }

    private static Resp getResp(String token, String cookie, String path) {
        try {
            return new Resp(200, get(token, cookie, path));
        } catch (Http.StatusException e) {
            return new Resp(e.code, null);
        } catch (Throwable t) {
            return new Resp(-1, null);
        }
    }

    /** 状态码异常原样抛出，避免把 401 当成网络失败。 */
    private static String get(String token, String cookie, String path) throws IOException {
        List<String> hs = new ArrayList<>();
        if (token != null && !token.isEmpty()) {
            hs.add("Authorization");
            hs.add(token.startsWith("Bearer ") ? token : "Bearer " + token);
        }
        if (cookie != null && !cookie.isEmpty()) {
            hs.add("Cookie");
            hs.add(cookie);
        }
        if (token != null && !token.isEmpty()) {
            hs.add("x-user-token");
            hs.add(token.startsWith("Bearer ") ? token.substring(7) : token);
        }
        hs.add("x-client-platform");
        hs.add("web");
        hs.add("Accept");
        hs.add("application/json");
        hs.add("User-Agent");
        hs.add("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
        String[] dev = DEVICE.get();
        if (dev != null) {
            for (int i = 0; i + 1 < dev.length; i += 2) {
                hs.add(dev[i]);
                hs.add(dev[i + 1]);
            }
        }
        return Http.get(BASE + path, hs.toArray(new String[0]), 9000);
    }

    /** 正常返回只记 code + 长度；非 0 才带上正文（避免把 200 条日志环刷满）。 */
    private static String brief(String body) {
        if (body == null) return "null";
        int code = -1;
        try {
            code = new JSONObject(body).optInt("code", -1);
        } catch (Throwable t) { Log.ignored("AccountApi", t);
            // 非 JSON，按异常处理
        }
        if (code == 0) return "code=0 len=" + body.length();
        return "code=" + code + " " + head(body);
    }

    private static String head(String s) {
        return s == null ? "" : s.substring(0, Math.min(3000, s.length()));
    }

    private static String offset(int sec) {
        int h = sec / 3600;
        int m = Math.abs(sec % 3600) / 60;
        return (h >= 0 ? "+" : "") + (h < 10 && h >= 0 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    private static double dbl(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }
}
