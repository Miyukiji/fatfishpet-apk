package com.dsha.whalepet;

import java.util.ArrayList;
import java.util.List;

/**
 * 余额文案整形（纯函数，可在 JVM 上单测）。
 *
 * 硬要求：人民币和美元**同一行同时**显示，顺序固定（CNY 在前），刷新时不跳动。
 */
public final class BalanceFmt {

    private static final String[] PREFERRED = {"CNY", "USD"};

    private BalanceFmt() {
    }

    public static String symbol(String currency) {
        String c = currency == null ? "" : currency.trim().toUpperCase();
        switch (c) {
            case "CNY": return "¥";
            case "USD": return "US$";
            case "EUR": return "€";
            case "JPY": return "JP¥";
            case "HKD": return "HK$";
            default: return c.isEmpty() ? "" : c + " ";
        }
    }

    public static String money(BalanceClient.Item item) {
        return symbol(item.currency) + item.total;
    }

    public static String balanceLine(List<BalanceClient.Item> items) {
        if (items == null || items.isEmpty()) return "--";
        List<BalanceClient.Item> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Integer.compare(rank(a.currency), rank(b.currency)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) sb.append("   ");
            sb.append(money(sorted.get(i)));
        }
        return sb.toString();
    }

    /**
     * 余额行（面板与数据页共用）：
     * 接口返回了币种就显示金额，账户不可用时在后面加注，不再整行变成 --；
     * 没有金额才显示 --，并带上原因（没填 Key / Key 不对 / 网络 / 超时）。
     */
    public static String statusLine(BalanceClient.Result r) {
        if (r == null) return "--";
        String why = r.error == null ? "" : r.error.trim();
        if (why.length() > 24) why = why.substring(0, 24) + "…";
        if (r.items != null && !r.items.isEmpty()) {
            String line = balanceLine(r.items);
            return r.ok || why.isEmpty() ? line : line + "（" + why + "）";
        }
        return why.isEmpty() ? "--" : "--（" + why + "）";
    }


    private static int rank(String currency) {
        String c = currency == null ? "" : currency.trim().toUpperCase();
        for (int i = 0; i < PREFERRED.length; i++) {
            if (PREFERRED[i].equals(c)) return i;
        }
        return PREFERRED.length;
    }
}
