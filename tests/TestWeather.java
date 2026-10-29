package com.dsha.whalepet;

/**
 * 天气解析的纯 JVM 单测：wttr.in j1 与 Open-Meteo 两套返回。
 *
 * 两个必须守住的点：
 *   1. weatherDesc / areaName 是 [{value:"..."}] 嵌套数组，不能把整段 toString 显示到面板；
 *   2. 英文描述要按「长句优先」翻成中文（"Patchy rain nearby" 不能被 "rain" 抢先命中）。
 */
public final class TestWeather {

    private static int checks;
    private static int fails;

    private static void eq(String what, Object actual, Object expected) {
        checks++;
        if (actual == null ? expected != null : !actual.equals(expected)) {
            fails++;
            System.out.println("FAIL " + what + ": [" + actual + "] != [" + expected + "]");
        }
    }

    private static void yes(String what, boolean v) {
        checks++;
        if (!v) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    private static final String WTTR = "{"
            + "\"nearest_area\":[{\"areaName\":[{\"value\":\"Torino\"}]}],"
            + "\"current_condition\":[{"
            + "\"temp_C\":\"18\",\"FeelsLikeC\":\"17\",\"humidity\":\"55\","
            + "\"weatherDesc\":[{\"value\":\"Partly cloudy\"}]}],"
            + "\"weather\":["
            + "{\"maxtempC\":\"22\",\"mintempC\":\"12\",\"hourly\":[]},"
            + "{\"maxtempC\":\"31\",\"mintempC\":\"20\",\"hourly\":[{\"weatherDesc\":[{\"value\":\"Sunny\"}]}]}"
            + "]}";

    public static void main(String[] args) {
        // ---- wttr.in：完整返回 ----
        String[] out = Weather.parse(WTTR);
        eq("城市取 value", out[0], "Torino  多云  18°C");
        eq("体感+湿度", out[2], "体感 17° · 湿度 55%");
        eq("明日行", out[1], "明日 晴  20° ~ 31°");

        // ---- 缺字段 / 空对象 / null ----
        eq("null 输入", Weather.parse(null)[0], "查不到天气（网络？）");
        eq("空对象", Weather.parse("{}")[0], "查不到天气（网络？）");
        eq("坏 JSON", Weather.parse("{oops")[0], "查不到天气（网络？）");

        String noTemp = "{\"current_condition\":[{\"weatherDesc\":[{\"value\":\"Sunny\"}],\"humidity\":\"40\"}]}";
        eq("没有温度用 --", Weather.parse(noTemp)[0], "晴  --");

        // 没有 weatherDesc 时退回 lang_zh（wttr 的 lang=zh 字段）
        String zhField = "{\"current_condition\":[{\"temp_C\":\"9\",\"lang_zh\":[{\"value\":\"小雨\"}]}]}";
        eq("lang_zh 兜底", Weather.parse(zhField)[0], "小雨  9°C");

        // 明日缺 hourly：仍给温度区间，只是没有描述
        String noHourly = "{\"weather\":[{},{\"maxtempC\":\"31\",\"mintempC\":\"20\",\"hourly\":[]}]}";
        eq("明日无描述", Weather.parse(noHourly)[1], "明日 20° ~ 31°");

        // ---- 英文描述翻译：长句优先 ----
        eq("长句优先", Weather.zh("Patchy rain nearby"), "局部有雨");
        eq("精确天气", Weather.zh("Sunny"), "晴");
        eq("雷阵雨", Weather.zh("Patchy light rain with thunder"), "雷阵雨");
        eq("空串", Weather.zh(""), "");
        eq("null", Weather.zh(null), "");
        eq("未知描述保留原文", Weather.zh("Volcanic ash"), "Volcanic ash");

        // ---- Open-Meteo ----
        String om = "{\"current\":{\"weather_code\":0,\"temperature_2m\":21.6},"
                + "\"daily\":{\"weather_code\":[0,3],"
                + "\"temperature_2m_max\":[25.4,19.6],\"temperature_2m_min\":[14.2,11.5]}}";
        String[] omOut = Weather.parseOpenMeteo(om, "Torino");
        eq("Open-Meteo 当前", omOut[0], "Torino  晴 22°C");
        eq("Open-Meteo 明日", omOut[1], "明日 阴 12° ~ 20°");
        eq("Open-Meteo 无地名", Weather.parseOpenMeteo(om, null)[0], "晴 22°C");
        eq("Open-Meteo 坏 JSON", Weather.parseOpenMeteo("nope", "X"), null);

        String omNoDaily = "{\"current\":{\"weather_code\":3,\"temperature_2m\":5},\"daily\":{}}";
        String[] oneDay = Weather.parseOpenMeteo(omNoDaily, "");
        eq("无 daily 第一行", oneDay[0], "阴 5°C");
        eq("无 daily 第二行空", oneDay[1], "");

        // ---- WMO 代码分档 ----
        eq("WMO 2 少云", wmoLine(2), "少云 10°C");
        eq("WMO 3 阴", wmoLine(3), "阴 10°C");
        eq("WMO 45 雾", wmoLine(45), "雾 10°C");
        eq("WMO 57 毛毛雨", wmoLine(57), "毛毛雨 10°C");
        eq("WMO 71 雪", wmoLine(71), "雪 10°C");
        eq("WMO 80 阵雨", wmoLine(80), "阵雨 10°C");
        eq("WMO 86 阵雪", wmoLine(86), "阵雪 10°C");
        eq("WMO 95 雷雨", wmoLine(95), "雷雨 10°C");
        yes("WMO 未知不为空", wmoLine(4) != null && wmoLine(4).length() > 0);

        System.out.println("TestWeather: " + checks + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }

    private static String wmoLine(int code) {
        String json = "{\"current\":{\"weather_code\":" + code + ",\"temperature_2m\":10},\"daily\":{}}";
        return Weather.parseOpenMeteo(json, "")[0];
    }
}
