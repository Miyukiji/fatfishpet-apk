package com.dsha.whalepet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * DSH 本地数据桥的客户端。
 *
 * 地址是**手机自己**的 127.0.0.1:3080（DSH 的 Web 服务；容器与宿主共享网络命名空间，
 * 所以 App 直接连得上）。两个来源，按优先级回落：
 *
 *   1. 我写的 whalepet-bridge 插件：/api/whalepet/status   ← 有 agent 状态，做"状态联动"
 *   2. 鲸鱼娘插件（未卸载时会话可直接用）：/api/whale-pet/usage ← 只有用量，没有状态
 *
 * 两边的 usage 字段完全一致（bridge 是照抄插件那套账本模块），所以解析只有一份。
 */
public final class Bridge {

    public static final String BASE = "http://127.0.0.1:3080";
    /** 可覆盖的数据源地址（朋友的 DSH 端不在本机时填它的地址，例如 http://192.0.2.10:3080）。 */
    private static volatile String base = BASE;
    /** 是否已经尝试过自动发现（失败就沿用 BASE，不无限阻塞）。 */
    private static volatile boolean discoverTried = false;
    /**
     * 桥每次启动随机生成的本地令牌（UDP 发现时带回）。
     * 回环端口对所有 App 共享，没有它任何本机应用都能假冒桌宠放行审批。
     */
    private static volatile String token = "";

    /** 向 127.0.0.1:47777 发现桥的端口与令牌；失败返回 null。 */
    private static String discover(int timeoutMs) {
        java.net.DatagramSocket s = null;
        try {
            s = new java.net.DatagramSocket();
            s.setSoTimeout(timeoutMs);
            byte[] q = "WHALEPET_DISCOVER".getBytes("UTF-8");
            s.send(new java.net.DatagramPacket(q, q.length,
                    java.net.InetAddress.getByName("127.0.0.1"), 47777));
            byte[] b = new byte[512];
            java.net.DatagramPacket r = new java.net.DatagramPacket(b, b.length);
            s.receive(r);
            JSONObject js = new JSONObject(new String(b, 0, r.getLength(), "UTF-8"));
            int port = js.optInt("port", 0);
            String tk = js.optString("token", "");
            if (port > 0 && !tk.isEmpty()) {
                base = "http://127.0.0.1:" + port;
                token = tk;
                return base;
            }
        } catch (Throwable t) {
            Log.ignored("Bridge.discover", t);
        } finally {
            if (s != null) s.close();
        }
        return null;
    }

    private static String base() {
        if (!discoverTried) {
            synchronized (Bridge.class) {
                if (!discoverTried) {
                    discover(700);
                    discoverTried = true;
                    Bridge.diag("discover", "base=" + base + " token=" + (token.isEmpty() ? "no" : "yes"));
                }
            }
        }
        return base;
    }

    public static String token() {
        return token;
    }

    /** 桥重启后端口/令牌会换：收到 401 时强制重发现一次。 */
    static synchronized void rediscover() {
        token = "";
        base = BASE;
        discoverTried = false;
        discover(700);
        discoverTried = true;
    }

    public static void setBase(String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isEmpty()) {
            // 空值 = 交给自动发现；以前这里无条件 discoverTried=true，把发现功能整段关死了
            base = BASE;
            token = "";
            discoverTried = false;
            return;
        }
        base = v;
        discoverTried = true;
    }

    /** 每次请求都带令牌（没有就不带，兼容未升级的桥）。 */
    private static String[] jsonHeaders() {
        String t = token;
        if (t.isEmpty()) return new String[]{"Accept", "application/json"};
        return new String[]{"Accept", "application/json", "x-whalepet-token", t};
    }

    public static String baseUrl() {
        return base;
    }
    private static final TimeZone BEIJING = TimeZone.getTimeZone("Asia/Shanghai");

    private Bridge() {
    }

    // ---------------- 状态 ----------------

    public static final class Status {
        public boolean bridge;
        public String status = "";
        public String text = "";
        public long since;
        public long at;
        /** "用时 42s · ¥0.31" 这种，来自桥的 lastDone；没有就空串。 */
        public String lastDone = "";
        public double todayCost = Double.NaN;
        public long todayTokens = -1L;
        /** 本会话累计花费（CNY）；NaN = 拿不到。 */
        public double sessionCost = Double.NaN;
        /** 账本窗口内（14 天）的本机累计。 */
        public double windowCost = Double.NaN;
        /** 永久累计（桥按天落盘，重启不丢）。 */
        public double lifetimeCost = Double.NaN;
        public double cacheHit = Double.NaN;
        /** DSH 正在等用户回应（提问/计划确认），不是审批。 */
        public boolean needUser;
        public String ask = "";

        /** 只按状态区分：正文每个 chunk 都在变，带上正文会让联动疯狂重播。 */
        public String key() {
            return status + (needUser ? "|need" : "");
        }
    }


    private static Status parseStatus(JSONObject o) {
        if (o == null) return null;
        Status s = new Status();
        s.bridge = true;
        s.status = o.optString("status", "idle");
        s.text = o.optString("text", "");
        s.since = o.optLong("since", 0L);
        s.needUser = o.optBoolean("needUser", false);
        s.ask = o.optString("ask", "");
        s.at = o.optLong("at", System.currentTimeMillis());
        JSONObject done = o.optJSONObject("lastDone");
        if (done != null) {
            long dur = done.optLong("durSec", -1L);
            double cost = done.optDouble("costCny", Double.NaN);
            StringBuilder sb = new StringBuilder();
            if (dur >= 0) sb.append(Txt.s(R.string.br_dur_prefix, "用时")).append(" ")
                    .append(dur).append("s");
            if (!Double.isNaN(cost)) {
                if (sb.length() > 0) {
                    sb.append(" ").append(Txt.s(R.string.br_sep, "·")).append(" ");
                }
                sb.append("¥").append(fmt(cost));
            }
            s.lastDone = sb.toString();
        }
        JSONObject today = o.optJSONObject("today");
        if (today != null) {
            s.todayCost = today.optDouble("costCny", Double.NaN);
            s.todayTokens = today.optLong("tokens", -1L);
            s.cacheHit = today.optDouble("cacheHitPercent", Double.NaN);
        }
        s.sessionCost = o.optDouble("sessionCostCny", Double.NaN);
        s.lifetimeCost = o.optDouble("lifetimeCostCny", Double.NaN);
        return s;
    }

    // ---------------- 诊断上报（无 logcat 时排查用） ----------------

    private static final java.util.concurrent.ExecutorService DIAG =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pet-diag");
                t.setDaemon(true);
                return t;
            });

    /** 把一行关键事件发到本机桥（失败就算了，绝不影响主流程）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_PENDING =
            new java.util.concurrent.atomic.AtomicInteger();

    public static void diag(String tag, String msg) {
        // 背压：桥没开/网络卡时，排队超过 64 条就直接丢（诊断日志丢了无所谓，别拖垮进程）
        if (DIAG_PENDING.get() > 64) return;
        DIAG_PENDING.incrementAndGet();
        try {
            final String body = new JSONObject()
                    .put("tag", tag == null ? "?" : tag)
                    .put("msg", msg == null ? "" : msg)
                    .toString();
            DIAG.execute(() -> {
                try {
                    // 诊断只发本机：数据源可能被改成别人的 DSH，账户数据不该跟着外发。
                    // 注意不能调 base()：它在首次调用时会阻塞 700ms 做 UDP 发现，而 diag 会在主线程被调。
                    String host = discoverTried ? base : BASE;
                    if (!host.startsWith("http://127.0.0.1:")) host = BASE;
                    Http.postJson(host + "/api/whalepet/log", body, jsonHeaders(), 1500);
                } catch (Throwable ignored) {
                    // 桥没开就算了
                } finally {
                    DIAG_PENDING.decrementAndGet();
                }
            });
        } catch (Throwable ignored) {
            // 连 JSON 都构造失败就放弃
        }
    }

    // ---------------- 审批（桌宠里确认命令） ----------------

    public static final class Approval {
        public String id;
        public String toolName;
        public String reason;
    }

    /** 一次请求拿「状态 + 待审批」。 */
    public static final class Poll {
        public Status status;
        public Approval pending;
        /** 长轮询的签名，下次带回来。 */
        public String seq = "";
    }

    /** 长轮询：状态没变就挂起，最多 timeoutMs。 */
    public static Poll wait(String since, int timeoutMs) {
        try {
            String q = java.net.URLEncoder.encode(since == null ? "" : since, "UTF-8");
            JSONObject o = getJson("/api/whalepet/wait?timeout=" + (timeoutMs - 1000)
                    + "&since=" + q, timeoutMs);
            if (o == null) return null;
            Poll p = new Poll();
            p.seq = o.optString("seq", "");
            p.status = parseStatus(o);
            JSONObject pa = o.optJSONObject("pending");
            if (pa != null && !pa.optString("id", "").isEmpty()) {
                p.pending = new Approval();
                p.pending.id = pa.optString("id", "");
                p.pending.toolName = pa.optString("toolName", "");
                p.pending.reason = pa.optString("reason", "");
            }
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把一条 JSON 解析成 Poll（SSE 每条 data 复用）。 */
    public static Poll parsePoll(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Poll out = new Poll();
            out.seq = o.optString("seq", "");
            out.status = parseStatus(o);
            JSONObject p = o.optJSONObject("pending");
            if (p != null && !p.optString("id", "").isEmpty()) {
                Approval a = new Approval();
                a.id = p.optString("id", "");
                a.toolName = p.optString("toolName", "");
                a.reason = p.optString("reason", "");
                out.pending = a;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public static Poll poll(int timeoutMs) {
        Poll out = new Poll();
        JSONObject o = getJson("/api/whalepet/poll", timeoutMs);
        if (o == null) return out;
        out.status = parseStatus(o);
        JSONObject p = o.optJSONObject("pending");
        if (p != null && !p.optString("id", "").isEmpty()) {
            Approval a = new Approval();
            a.id = p.optString("id", "");
            a.toolName = p.optString("toolName", "");
            a.reason = p.optString("reason", "");
            out.pending = a;
        }
        return out;
    }


    /** 回传决定：允许 / 拒绝。 */
    public static boolean decide(String id, boolean allow, int timeoutMs) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JSONObject body = new JSONObject();
                body.put("id", id);
                body.put("decision", allow ? "allow" : "deny");
                String out = Http.postJson(base() + "/api/whalepet/approval",
                        body.toString(), jsonHeaders(), timeoutMs);
                JSONObject o = new JSONObject(out);
                return o.optBoolean("ok", false);
            } catch (Http.StatusException e) {
                if (e.code == 401 && attempt == 0) { rediscover(); continue; }
                return false;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    // ---------------- 用量 ----------------

    public static final class Usage {
        public boolean ok;
        public String error = "";
        /** 今日花费（CNY）；NaN = 没数据（不是 0 元）。 */
        public double todayCost = Double.NaN;
        public long todayTokens = -1L;
        /** 本会话累计花费（CNY）；NaN = 拿不到。 */
        public double sessionCost = Double.NaN;
        /** 账本窗口内（14 天）的本机累计。 */
        public double windowCost = Double.NaN;
        /** 永久累计（桥按天落盘，重启不丢）。 */
        public double lifetimeCost = Double.NaN;
        public double cacheHit = Double.NaN;
        public int calls = -1;
        /** 今日每小时的开销，只列有消费的，最多 8 行。 */
        public String hours = "";
    }

    public static Usage usage(int timeoutMs) {
        // 只读**我写的数据桥**。不再去试鲸鱼娘插件的 /api/whale-pet/usage：
        // 那条属于别人的插件，用户会自己删掉，依赖它只会让行为随装删漂移。
        JSONObject o = getJson("/api/whalepet/usage?days=2&hours=24", timeoutMs);
        if (o == null) {
            Usage u = new Usage();
            u.error = Txt.s(R.string.br_no_bridge, "没有可用的用量数据桥");
            return u;
        }
        return parseUsage(o);
    }

    static Usage parseUsage(JSONObject o) {
        Usage u = new Usage();
        if (!o.optBoolean("ok", false)) {
            u.error = o.optString("error", Txt.s(R.string.br_stats_fail, "统计接口返回失败"));
            return u;
        }
        final String today = localDay();
        JSONArray hours = o.optJSONArray("hours");
        // 累加器从 0 起：NaN 起步会让 cost 永远是 NaN，-1 起步会让 tokens 少 1
        double cost = 0;
        long tokens = 0, cacheRead = 0, promptIn = 0;
        int calls = 0;
        boolean any = false;
        StringBuilder detail = new StringBuilder();
        int lines = 0;
        if (hours != null) {
            for (int i = 0; i < hours.length(); i++) {
                JSONObject h = hours.optJSONObject(i);
                if (h == null) continue;
                String local = hourLocalDay(h);
                if (local != null ? !today.equals(local) : !today.equals(h.optString("day", ""))) continue;
                any = true;
                double c = h.optDouble("costCny", 0);
                long total = h.optLong("total", 0);
                JSONObject tk = h.optJSONObject("tokens");
                if (tk != null) {
                    total = tk.optLong("total", total);
                    long read = tk.optLong("cacheRead", 0);
                    cacheRead += read;
                    // 与桥同口径：prompt 侧 = input（未命中）+ cacheRead + cacheWrite
                    promptIn += tk.optLong("input", 0) + read + tk.optLong("cacheWrite", 0);
                }
                calls += h.optInt("calls", 0);
                cost += c;
                tokens += total;
                if (c > 0) {
                    if (lines < 8) {
                        if (lines > 0) {
                            detail.append(lines % 2 == 0 ? "\n" : "   ");
                        }
                        detail.append(String.format(Locale.US, "%02d:00 ¥%s",
                                h.optInt("hour", 0), fmt(c)));
                        lines++;
                    }
                }
            }
        }
        u.ok = true;
        u.todayCost = any ? cost : Double.NaN;
        u.todayTokens = any ? tokens : -1L;
        u.calls = any ? calls : -1;
        u.cacheHit = promptIn > 0 ? (100.0 * cacheRead / promptIn) : Double.NaN;
        // 桥直接给了今天的日桶（与 poll 的 today 同源），有就以它为准；老版本桥没有就用上面的分时累加
        JSONObject totals = any ? null : o.optJSONObject("totals");   // 有小时数据就以小时为准
        JSONObject td = totals == null ? null : totals.optJSONObject("today");
        if (td != null) {
            double c = td.optDouble("costCny", Double.NaN);
            if (!Double.isNaN(c)) u.todayCost = c;
            JSONObject tk = td.optJSONObject("tokens");
            if (tk != null && tk.has("total")) u.todayTokens = tk.optLong("total", u.todayTokens);
            if (td.has("calls")) u.calls = td.optInt("calls", u.calls);
            double hit = td.optDouble("cacheHitPercent", Double.NaN);
            if (!Double.isNaN(hit)) u.cacheHit = hit;
        }
        u.hours = detail.toString();
        u.sessionCost = o.optDouble("sessionCostCny", Double.NaN);
        u.lifetimeCost = o.optDouble("lifetimeCostCny", Double.NaN);
        JSONArray days = o.optJSONArray("days");
        if (days != null) {
            double sum = 0;
            boolean hasDay = false;
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null) continue;
                double c = d.optDouble("costCny", Double.NaN);
                if (!Double.isNaN(c)) {
                    sum += c;
                    hasDay = true;
                }
            }
            if (hasDay) u.windowCost = sum;
        }
        return u;
    }


    // ---------------- 底层 ----------------

    private static JSONObject getJson(String path, int timeoutMs) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String body = Http.get(base() + path, jsonHeaders(), timeoutMs);
                if (body == null) return null;
                body = body.trim();
                if (!body.startsWith("{")) return null;
                return new JSONObject(body);
            } catch (Http.StatusException e) {
                if (e.code == 401 && attempt == 0) { rediscover(); continue; }
                return null;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 设备本地今天（跟随系统时区与夏令时）。 */
    public static String localDay() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(java.util.TimeZone.getDefault());
        return f.format(new Date());
    }

    /** 小时桶 → 设备本地的日期串。桥的 index 是北京钟点序：epoch = (index - 8) 小时。 */
    private static String hourLocalDay(JSONObject h) {
        long idx = h.optLong("index", -1L);
        if (idx <= 0) return null;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(java.util.TimeZone.getDefault());
        return f.format(new Date((idx - 8L) * 3600_000L));
    }


    public static String day() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(BEIJING);
        return f.format(new Date());
    }

    public static String fmt(double v) {
        if (v > 0 && v < 0.01) return String.format(java.util.Locale.US, "%.3f", v);
        if (Double.isNaN(v)) return "--";
        if (v >= 100) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.2f", v);
    }
}
