package com.dsha.whalepet;

/**
 * wttr.in 的 j1 JSON → 面板三行文字。纯函数，不 import android，可在 JVM 单测。
 *
 * 踩过的坑：`weatherDesc` / `areaName` 都是 `[{value:"..."}]` 这种嵌套结构，
 * 直接当字符串取会在面板上显示成 `[{value=Vanchiglia}]`。
 */
public final class Weather {

    private Weather() {
    }
    /** WMO 天气代码 → 本地化短词（单测环境回退中文）。 */
    private static String wmo(int code) {
        if (code == 0) return Txt.s(R.string.wx_clear, "晴");
        if (code <= 2) return Txt.s(R.string.wx_partly, "少云");
        if (code == 3) return Txt.s(R.string.wx_overcast, "阴");
        if (code >= 45 && code <= 48) return Txt.s(R.string.wx_fog, "雾");
        if (code >= 51 && code <= 57) return Txt.s(R.string.wx_drizzle, "毛毛雨");
        if (code >= 61 && code <= 67) return Txt.s(R.string.wx_rain, "雨");
        if (code >= 71 && code <= 77) return Txt.s(R.string.wx_snow, "雪");
        if (code >= 80 && code <= 82) return Txt.s(R.string.wx_showers, "阵雨");
        if (code >= 85 && code <= 86) return Txt.s(R.string.wx_snow_showers, "阵雪");
        if (code >= 95) return Txt.s(R.string.wx_thunder, "雷雨");
        return Txt.s(R.string.wx_cloudy, "多云");
    }

    private static int numInt(Object o) {
        try { return (int) Math.round(Double.parseDouble(String.valueOf(o))); } catch (Throwable t) { return 0; }
    }

    private static float numF(Object o) {
        try { return Float.parseFloat(String.valueOf(o)); } catch (Throwable t) { return 0f; }
    }

    /** Open-Meteo 返回 → 面板两行；解析失败返回 null。 */
    public static String[] parseOpenMeteo(String json, String place) {
        try {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) MiniJson.parse(json);
            java.util.Map<?, ?> cur = (java.util.Map<?, ?>) m.get("current");
            java.util.Map<?, ?> daily = (java.util.Map<?, ?>) m.get("daily");
            int code = numInt(cur.get("weather_code"));
            int temp = Math.round(numF(cur.get("temperature_2m")));
            java.util.List<?> max = (java.util.List<?>) daily.get("temperature_2m_max");
            java.util.List<?> min = (java.util.List<?>) daily.get("temperature_2m_min");
            java.util.List<?> dcode = (java.util.List<?>) daily.get("weather_code");
            String line1 = (place == null || place.isEmpty() ? "" : place + "  ") + wmo(code) + " " + temp + "°C";
            String line2 = "";
            if (max != null && max.size() > 1 && min != null && min.size() > 1) {
                line2 = Txt.s(R.string.wx_tomorrow_prefix, "明日") + " " + wmo(numInt(dcode.get(1))) + " "
                        + Math.round(numF(min.get(1))) + "° ~ " + Math.round(numF(max.get(1))) + "°";
            }
            return new String[]{line1, line2};
        } catch (Throwable t) {
            return null;
        }
    }



    /**
     * 常见天气描述的中英对照。
     *
     * wttr.in 的 `lang=zh` 覆盖不全（实测它返回的 `lang_zh` 值仍是英文），所以自己兜一层。
     * 命中规则：小写后按"包含"匹配，先匹配长句（"Patchy rain nearby" 要在 "rain" 之前）。
     * 没命中就原样显示英文 —— 宁可显示原文，也不要瞎猜成"晴"。
     */
    private static final String[][] ZH = {
            {"patchy light rain with thunder", "雷阵雨"},
            {"moderate or heavy rain with thunder", "雷雨"},
            {"thundery outbreaks possible", "可能有雷阵雨"},
            {"patchy light drizzle", "零星毛毛雨"},
            {"light freezing rain", "冻雨"},
            {"patchy rain nearby", "局部有雨"},
            {"patchy rain possible", "可能有雨"},
            {"patchy snow nearby", "局部有雪"},
            {"patchy light rain", "零星小雨"},
            {"torrential rain", "暴雨"},
            {"moderate rain", "中雨"},
            {"heavy rain", "大雨"},
            {"light rain", "小雨"},
            {"light drizzle", "毛毛雨"},
            {"moderate snow", "中雪"},
            {"heavy snow", "大雪"},
            {"light snow", "小雪"},
            {"blowing snow", "吹雪"},
            {"freezing fog", "冻雾"},
            {"partly cloudy", "多云"},
            {"smoky haze", "烟霾"},
            {"thunderstorm", "雷暴"},
            {"overcast", "阴"},
            {"cloudy", "多云"},
            {"sunny", "晴"},
            {"clear", "晴"},
            {"mist", "薄雾"},
            {"fog", "雾"},
            {"haze", "霾"},
            {"smoke", "烟霾"},
            {"sleet", "雨夹雪"},
            {"snow", "雪"},
            {"rain", "雨"},
    };

    /** 与 ZH 表一一对应的本地化资源。 */
    private static final int[] ZH_IDS = {
            R.string.wx_desc_00,
            R.string.wx_desc_01,
            R.string.wx_desc_02,
            R.string.wx_desc_03,
            R.string.wx_desc_04,
            R.string.wx_desc_05,
            R.string.wx_desc_06,
            R.string.wx_desc_07,
            R.string.wx_desc_08,
            R.string.wx_desc_09,
            R.string.wx_desc_10,
            R.string.wx_desc_11,
            R.string.wx_desc_12,
            R.string.wx_desc_13,
            R.string.wx_desc_14,
            R.string.wx_desc_15,
            R.string.wx_desc_16,
            R.string.wx_desc_17,
            R.string.wx_desc_18,
            R.string.wx_desc_19,
            R.string.wx_desc_20,
            R.string.wx_desc_21,
            R.string.wx_desc_22,
            R.string.wx_desc_23,
            R.string.wx_desc_24,
            R.string.wx_desc_25,
            R.string.wx_desc_26,
            R.string.wx_desc_27,
            R.string.wx_desc_28,
            R.string.wx_desc_29,
            R.string.wx_desc_30,
            R.string.wx_desc_31,
            R.string.wx_desc_32,
    };

    /** 英文天气描述 → 本地化短词；翻不到就返回原文。 */
    static String zh(String desc) {
        if (desc == null) return "";
        String d = desc.trim();
        if (d.isEmpty()) return "";
        String low = d.toLowerCase(java.util.Locale.US);
        for (int i = 0; i < ZH.length; i++) {
            if (low.contains(ZH[i][0])) return Txt.s(ZH_IDS[i], ZH[i][1]);
        }
        return d;
    }

    /** @return [当前一行, 明日一行, 补充一行（体感/湿度）] */
    public static String[] parse(String body) {
        // 返回 [当前, 明日, 补充]三段，正好对应面板里的三个 TextView
        String[] out = {Txt.s(R.string.wx_unknown, "查不到天气（网络？）"), "", ""};
        if (body == null) return out;
        try {
            Object root = MiniJson.parse(body);
            if (!(root instanceof java.util.Map)) return out;
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) root;

            java.util.Map<?, ?> area = firstMap(m.get("nearest_area"));
            java.util.Map<?, ?> cur = firstMap(m.get("current_condition"));

            // weatherDesc / areaName 都是 [{value:"..."}] 这种嵌套结构，
            // 直接取字符串会得到 "[{value=...}]" —— 真机上就是这么显示出乱码的。
            String city = area == null ? "" : nestedValue(area.get("areaName"));
            if (cur != null) {
                String desc = nestedValue(cur.get("weatherDesc"));
                if (desc.isEmpty()) desc = nestedValue(cur.get("lang_zh"));
                desc = zh(desc);
                String temp = str(cur.get("temp_C"));
                String feel = str(cur.get("FeelsLikeC"));
                String hum = str(cur.get("humidity"));

                StringBuilder head = new StringBuilder();
                if (!city.isEmpty()) head.append(city).append("  ");
                if (!desc.isEmpty()) head.append(desc).append("  ");
                String dash0 = Txt.s(R.string.wx_dash, "--");
                head.append(dash0.equals(temp) ? dash0 : temp + "°C");
                out[0] = head.toString();

                StringBuilder extra = new StringBuilder();
                String dash = Txt.s(R.string.wx_dash, "--");
                if (!dash.equals(feel)) {
                    extra.append(Txt.s(R.string.wx_feels_prefix, "体感")).append(" ").append(feel).append("°");
                }
                if (!dash.equals(hum)) {
                    if (extra.length() > 0) {
                        extra.append(" ").append(Txt.s(R.string.wx_sep, "·")).append(" ");
                    }
                    extra.append(Txt.s(R.string.wx_humidity_prefix, "湿度")).append(" ").append(hum).append("%");
                }
                out[2] = extra.toString();
            }

            java.util.List<?> days = asList(m.get("weather"));
            if (days.size() > 1) {
                java.util.Map<?, ?> d = asMap(days.get(1));
                if (d != null) {
                    String hi = str(d.get("maxtempC"));
                    String lo = str(d.get("mintempC"));
                    String desc = "";
                    java.util.List<?> hours = asList(d.get("hourly"));
                    if (!hours.isEmpty()) {
                        java.util.Map<?, ?> h4 = asMap(hours.get(Math.min(4, hours.size() - 1)));
                        if (h4 != null) desc = zh(nestedValue(h4.get("weatherDesc")));
                    }
                    out[1] = Txt.s(R.string.wx_tomorrow_prefix, "明日") + " " + (desc.isEmpty() ? "" : desc + "  ")
                            + ("--".equals(lo) ? "" : lo + "°") + " ~ "
                            + ("--".equals(hi) ? "" : hi + "°");
                }
            }
        } catch (Throwable t) { Log.ignored("Weather", t);
            // 解析失败用兜底
        }
        return out;
    }

    private static java.util.List<?> asList(Object o) {
        return o instanceof java.util.List ? (java.util.List<?>) o : java.util.Collections.emptyList();
    }

    private static String str(Object o) {
        if (o == null) return "--";
        String v = String.valueOf(o).trim();
        return v.isEmpty() || "null".equals(v) ? "--" : v;
    }

    private static java.util.Map<?, ?> asMap(Object o) {
        return o instanceof java.util.Map ? (java.util.Map<?, ?>) o : null;
    }

    private static java.util.Map<?, ?> firstMap(Object o) {
        java.util.List<?> l = asList(o);
        return l.isEmpty() ? null : asMap(l.get(0));
    }

    /** 取 [{value:"x"}] 里的 x；不是这种结构就退回字符串本身。 */
    private static String nestedValue(Object o) {
        java.util.Map<?, ?> m = firstMap(o);
        if (m != null) return str(m.get("value"));
        String v = str(o);
        return "--".equals(v) ? "" : v;
    }
}
