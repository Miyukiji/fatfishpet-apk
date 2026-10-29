package com.dsha.whalepet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/** 最小 HTTP(S) GET（纯 Java，JVM 上也能跑，便于离线测试）。 */
public final class Http {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 非 2xx 时抛出，带上状态码，供上层区分 401（Key 不对）之类。 */
    public static final class StatusException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int code;

        StatusException(int code, String body) {
            super("HTTP " + code + (body.isEmpty() ? "" : "：" + body));
            this.code = code;
        }
    }

    private Http() {
    }

    /** 极简 POST JSON（审批决定 / 诊断日志用）。 */
    public static String postJson(String url, String body, int timeoutMs) throws IOException {
        return postJson(url, body, null, timeoutMs);
    }

    public static String postJson(String url, String body, String[] headers, int timeoutMs) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("content-type", "application/json; charset=utf-8");
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) {
                    c.setRequestProperty(headers[i], headers[i + 1]);
                }
            }
            try (java.io.OutputStream os = c.getOutputStream()) {
                os.write((body == null ? "" : body).getBytes(UTF8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String out = in == null ? "" : readAll(in);
            if (code < 200 || code >= 300) throw new StatusException(code, out);
            return out;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String get(String url, String[] headers, int timeoutMs) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            // 带凭据的请求不跟随重定向：HttpURLConnection 会把 Authorization 带到新地址
            if (hasHeader(headers, "Authorization")) c.setInstanceFollowRedirects(false);
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) {
                    c.setRequestProperty(headers[i], headers[i + 1]);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) throw new StatusException(code, trim(body));
            return body;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    private static boolean hasHeader(String[] headers, String name) {
        if (headers == null) return false;
        for (int i = 0; i + 1 < headers.length; i += 2) {
            if (name.equalsIgnoreCase(headers[i])) return true;
        }
        return false;
    }

    private static String trim(String s) {
        s = s.replace('\n', ' ').trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
