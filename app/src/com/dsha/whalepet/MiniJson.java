package com.dsha.whalepet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 解析器（纯 Java，不依赖 org.json）。
 *
 * 为什么要自己写：这样余额解析逻辑就能在普通 JVM 上跑单元测试，
 * 不用把整段逻辑锁死在 Android 运行时里。
 * 支持：对象 / 数组 / 字符串 / 整数 / 小数 / true / false / null / 反斜杠转义。
 */
public final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
        this.i = 0;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.i != text.length()) {
            throw new IllegalArgumentException("trailing data at " + p.i);
        }
        return v;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        skipWs();
        if (peek() == '}') { i++; return m; }
        while (true) {
            skipWs();
            String k = string();
            skipWs();
            if (peek() != ':') throw fail("expected ':'");
            i++;
            skipWs();
            m.put(k, value());
            skipWs();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw fail("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // [
        skipWs();
        if (peek() == ']') { i++; return l; }
        while (true) {
            skipWs();
            l.add(value());
            skipWs();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw fail("expected ',' or ']'");
        }
    }

    private String string() {
        if (peek() != '"') throw fail("expected '\"'");
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw fail("bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) i++;
            else break;
        }
        if (start == i) throw fail("unexpected '" + s.charAt(i) + "'");
        String t = s.substring(start, i);
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                // 超长整数退回 double
            }
        }
        return Double.parseDouble(t);
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, i)) throw fail("expected " + lit);
        i += lit.length();
    }

    private char peek() {
        if (i >= s.length()) throw fail("unexpected end");
        return s.charAt(i);
    }

    private void skipWs() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private IllegalArgumentException fail(String why) {
        return new IllegalArgumentException(why + " at " + i);
    }
}
