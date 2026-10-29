package com.dsha.whalepet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 余额接口解析与文案整形的纯 JVM 单测。
 *
 * 覆盖两类坑：
 *   1. 接口字段形态不稳（字符串 / 数字 / 缺失 / error 字段）；
 *   2. 人民币与美元必须同一行、顺序固定（CNY 在前），失败时不能把已有金额整行吞掉。
 */
public final class TestBalance {

    private static int checks;
    private static int fails;

    private static void eq(String what, Object actual, Object expected) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            fails++;
            System.out.println("FAIL " + what + ": [" + actual + "] != [" + expected + "]");
        }
    }

    public static void main(String[] args) {
        // ---- 币种符号 ----
        eq("symbol CNY", BalanceFmt.symbol("CNY"), "¥");
        eq("symbol usd 小写+空格", BalanceFmt.symbol(" usd "), "US$");
        eq("symbol EUR", BalanceFmt.symbol("EUR"), "€");
        eq("symbol JPY", BalanceFmt.symbol("JPY"), "JP¥");
        eq("symbol HKD", BalanceFmt.symbol("HKD"), "HK$");
        eq("symbol 未知币种", BalanceFmt.symbol("GBP"), "GBP ");
        eq("symbol 空", BalanceFmt.symbol(""), "");
        eq("symbol null", BalanceFmt.symbol(null), "");

        // ---- 正常返回：两种币种，字段是字符串 ----
        String two = "{\"balance_infos\":["
                + "{\"currency\":\"CNY\",\"total_balance\":\"110.00\",\"granted_balance\":\"10.00\",\"topped_up_balance\":\"100.00\"},"
                + "{\"currency\":\"USD\",\"total_balance\":\"8.50\",\"granted_balance\":\"0.00\",\"topped_up_balance\":\"8.50\"}]}";
        BalanceClient.Result r = BalanceClient.parse(two);
        eq("两种币种 ok", r.ok, Boolean.TRUE);
        eq("两种币种 items", r.items.size(), 2);
        eq("item0 currency", r.items.get(0).currency, "CNY");
        eq("item0 total", r.items.get(0).total, "110.00");
        eq("item0 granted", r.items.get(0).granted, "10.00");
        eq("item0 topped", r.items.get(0).topped, "100.00");
        eq("item1 currency", r.items.get(1).currency, "USD");
        eq("两种币种无错误", r.error, "");

        // ---- 数字金额统一两位小数；缺失 / 空串回 0 ----
        r = BalanceClient.parse("{\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":12}]}");
        eq("数字金额格式化", r.items.get(0).total, "12.00");
        eq("缺失 granted", r.items.get(0).granted, "0");
        eq("缺失 topped", r.items.get(0).topped, "0");

        r = BalanceClient.parse("{\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"\"}]}");
        eq("空串金额回 0", r.items.get(0).total, "0");

        r = BalanceClient.parse("{\"balance_infos\":[{\"total_balance\":\"1.00\"}]}");
        eq("缺省币种 CNY", r.items.get(0).currency, "CNY");

        // ---- is_available=false：仍带金额，但 ok=false 且给出原因 ----
        r = BalanceClient.parse("{\"is_available\":false,\"balance_infos\":[{\"currency\":\"CNY\",\"total_balance\":\"0.00\"}]}");
        eq("不可用 ok", r.ok, Boolean.FALSE);
        eq("不可用原因", r.error, "账户当前不可用（余额不足？）");
        eq("不可用仍有金额", r.items.get(0).total, "0.00");
        eq("不可用文案带金额", BalanceFmt.statusLine(r), "¥0.00（账户当前不可用（余额不足？））");

        // ---- 接口给了 error 字段 ----
        r = BalanceClient.parse("{\"error\":\"Invalid token\"}");
        eq("error 字段透传", r.error, "Invalid token");
        eq("error 时无金额", r.items.size(), 0);
        eq("error 文案", BalanceFmt.statusLine(r), "--（Invalid token）");

        // ---- 两种异常返回 ----
        r = BalanceClient.parse("{\"is_available\":true}");
        eq("没有币种时失败", r.ok, Boolean.FALSE);
        eq("没有币种的原因", r.error, "余额接口没有返回币种");
        eq("没有币种的文案", BalanceFmt.statusLine(r), "--（余额接口没有返回币种）");

        eq("非 JSON", BalanceClient.parse("<html>502</html>").error, "返回内容不是 JSON");
        eq("不是对象", BalanceClient.parse("[1,2]").error, "返回内容不是 JSON 对象");

        // ---- 余额行排序与拼接 ----
        List<BalanceClient.Item> items = new ArrayList<>(Arrays.asList(
                new BalanceClient.Item("USD", "8.50", "0.00", "8.50"),
                new BalanceClient.Item("CNY", "110.00", "10.00", "100.00")));
        eq("CNY 在前", BalanceFmt.balanceLine(items), "¥110.00   US$8.50");
        eq("空列表", BalanceFmt.balanceLine(new ArrayList<BalanceClient.Item>()), "--");
        eq("null 列表", BalanceFmt.balanceLine(null), "--");

        // ---- statusLine 的各种组合 ----
        eq("statusLine null", BalanceFmt.statusLine(null), "--");
        BalanceClient.Result ok = new BalanceClient.Result(true, "", items);
        eq("statusLine 正常", BalanceFmt.statusLine(ok), "¥110.00   US$8.50");
        BalanceClient.Result warn = new BalanceClient.Result(false, "查询超时（网络慢？）", items);
        eq("statusLine 有金额+原因", BalanceFmt.statusLine(warn), "¥110.00   US$8.50（查询超时（网络慢？））");
        BalanceClient.Result empty = new BalanceClient.Result(false, "", new ArrayList<BalanceClient.Item>());
        eq("statusLine 无金额无原因", BalanceFmt.statusLine(empty), "--");
        BalanceClient.Result longWhy = new BalanceClient.Result(false,
                "查询失败：连不上 api.deepseek.com，请检查网络后重试", new ArrayList<BalanceClient.Item>());
        String truncated = BalanceFmt.statusLine(longWhy);
        checks++;
        if (!truncated.endsWith("…）") || !truncated.startsWith("--（") || truncated.length() > 30) {
            fails++;
            System.out.println("FAIL 长原因截断: [" + truncated + "]");
        }

        System.out.println("TestBalance: " + checks + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
