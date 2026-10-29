package com.dsha.whalepet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 直接查 DeepSeek 官方开放平台的账户余额（纯 Java，可在 JVM 上单测）。
 *
 *   GET https://api.deepseek.com/user/balance
 *   Authorization: Bearer <你自己的 API Key>
 *
 * 这个接口是**免费**的：只做一次鉴权 GET，不消耗 token、不计费。
 * 和 dsh-whale-girl-pet 插件里用的是同一个官方接口，只是插件用的是 DSH 自己的凭据，
 * 这里用的是你在 App 里填的那把 key。
 */
public final class BalanceClient {

    public static final String URL = "https://api.deepseek.com/user/balance";

    public static final class Item {
        public final String currency;
        public final String total;
        public final String granted;
        public final String topped;

        Item(String currency, String total, String granted, String topped) {
            this.currency = currency;
            this.total = total;
            this.granted = granted;
            this.topped = topped;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final List<Item> items;

        /** 供调用方在超时等场景构造一个"失败"结果。 */
    public static Result failed(String reason) {
        return new Result(false, reason, java.util.Collections.emptyList());
    }

    Result(boolean ok, String error, List<Item> items) {
            this.ok = ok;
            this.error = error;
            this.items = items;
        }
    }

    private BalanceClient() {
    }

    public static Result fetch(String apiKey, int timeoutMs) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            return new Result(false, Txt.s(R.string.bal_no_key, "还没填 API Key"), new ArrayList<Item>());
        }
        String body;
        try {
            body = Http.get(URL, new String[]{"Authorization", "Bearer " + key,
                    "Accept", "application/json"}, timeoutMs);
        } catch (Http.StatusException e) {
            if (e.code == 401 || e.code == 403) {
                return new Result(false, String.format(Txt.s(R.string.bal_key_bad,
                        "API Key 不对（HTTP %1$d）"), e.code), new ArrayList<Item>());
            }
            return new Result(false, String.format(Txt.s(R.string.bal_http,
                    "查询失败：HTTP %1$d"), e.code), new ArrayList<Item>());
        } catch (java.net.SocketTimeoutException e) {
            return new Result(false, Txt.s(R.string.bal_timeout, "查询超时（网络慢？）"), new ArrayList<Item>());
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (m != null && m.contains("UnknownHost")) {
                m = Txt.s(R.string.bal_dns, "连不上 api.deepseek.com（网络）");
            }
            return new Result(false, String.format(Txt.s(R.string.bal_fail, "查询失败：%1$s"), m),
                    new ArrayList<Item>());
        }
        return parse(body);
    }

    @SuppressWarnings("unchecked")
    public static Result parse(String body) {
        Object root;
        try {
            root = MiniJson.parse(body);
        } catch (Exception e) {
            return new Result(false, Txt.s(R.string.bal_not_json, "返回内容不是 JSON"), new ArrayList<Item>());
        }
        if (!(root instanceof Map)) {
            return new Result(false, Txt.s(R.string.bal_not_obj, "返回内容不是 JSON 对象"), new ArrayList<Item>());
        }
        Map<String, Object> m = (Map<String, Object>) root;
        List<Item> items = new ArrayList<>();
        Object infos = m.get("balance_infos");
        if (infos instanceof List) {
            for (Object o : (List<Object>) infos) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> mi = (Map<String, Object>) o;
                items.add(new Item(
                        str(mi.get("currency"), "CNY"),
                        amount(mi.get("total_balance")),
                        amount(mi.get("granted_balance")),
                        amount(mi.get("topped_up_balance"))));
            }
        }
        if (items.isEmpty()) {
            // 余额接口对无权限/异常账户会给出 error 字段
            return new Result(false, str(m.get("error"),
                    Txt.s(R.string.bal_no_currency, "余额接口没有返回币种")), items);
        }
        Object avail = m.get("is_available");
        boolean ok = !(avail instanceof Boolean) || (Boolean) avail;
        return new Result(ok, ok ? "" : Txt.s(R.string.bal_unavailable, "账户当前不可用（余额不足？）"), items);
    }

    /** 官方返回的是 "110.00" 这样的字符串；万一给的是数字，也统一成两位小数（别显示 110.0 / 1.0E-4）。 */
    private static String amount(Object o) {
        if (o instanceof Number) {
            return String.format(java.util.Locale.US, "%.2f", ((Number) o).doubleValue());
        }
        return str(o, "0");
    }

    private static String str(Object o, String dflt) {
        if (o == null) return dflt;
        String s = String.valueOf(o);
        return s.length() == 0 ? dflt : s;
    }
}
