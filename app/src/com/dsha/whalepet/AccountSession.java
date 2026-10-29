package com.dsha.whalepet;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.Charset;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 平台 userToken / 会话 cookie / 应用密钥：AES-GCM（AndroidKeyStore）加密存本地。
 *
 * 历史包袱：0.2.x 的登录流程会把平台的 authorization 原样写进 prefs（不带 IV），
 * cookie 也是明文。现在统一走加密，并在启动时把旧值重加密一次（KEY_VERSION 5）。
 */
public final class AccountSession {

    /** 密钥版本：升级时换别名并重加密已有数据。 */
    private static final int KEY_VERSION = 5;
    private static final String ALIAS = "pet_acct5";
    private static final String[] OLD_ALIASES = {"pet_acct4", "pet_acct3"};
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private AccountSession() {
    }

    public static boolean has(Context c) {
        return !token(c).isEmpty() || cookie(c).length() > 20;
    }

    public static String token(Context c) {
        String v = tokenWith(c, ALIAS);
        if (!v.isEmpty()) return v;
        for (String alias : OLD_ALIASES) {
            v = tokenWith(c, alias);
            if (!v.isEmpty()) return v;
        }
        return "";
    }

    private static String tokenWith(Context c, String alias) {
        SharedPreferences sp = Prefs.acct(c);
        String b64 = sp.getString("t", "");
        String iv = sp.getString("i", "");
        if (b64.isEmpty()) return "";
        if (iv.isEmpty()) return b64;          // 旧版明文（rotateIfNeeded 会重加密）
        try {
            Cipher cip = Cipher.getInstance("AES/GCM/NoPadding");
            cip.init(Cipher.DECRYPT_MODE, key(alias), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cip.doFinal(Base64.decode(b64, Base64.NO_WRAP)), UTF8);
        } catch (Throwable t) {
            return "";
        }
    }

    public static void save(Context c, String token) {
        save(c, token, ALIAS);
    }

    static void save(Context c, String token, String alias) {
        if (token == null || token.trim().isEmpty()) return;
        try {
            Cipher cip = Cipher.getInstance("AES/GCM/NoPadding");
            cip.init(Cipher.ENCRYPT_MODE, key(alias));
            byte[] data = cip.doFinal(token.trim().getBytes(UTF8));
            Prefs.acct(c).edit()
                    .putString("t", Base64.encodeToString(data, Base64.NO_WRAP))
                    .putString("i", Base64.encodeToString(cip.getIV(), Base64.NO_WRAP))
                    .remove("c")              // 换了登录态，上一个账户的缓存数据作废
                    .apply();
        } catch (Throwable t) { Log.ignored("AccountSession", t);
            // 存不进去当没登录
        }
    }

    /** 原样保存平台的 authorization 与配套设备头（调用时逐字回放）——加密存。 */
    public static void saveAuth(Context c, org.json.JSONObject o) {
        String auth = o.optString("authorization", "");
        if (auth.isEmpty()) return;
        save(c, auth);
        SharedPreferences.Editor e = Prefs.acct(c).edit();
        for (String k : new String[]{"x-device-id", "x-device-model", "x-client-bundle-id",
                "x-client-version", "x-client-locale", "x-client-timezone-offset"}) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) e.putString("h_" + k, v);
        }
        e.apply();
        Prefs.acct(c).edit().remove("c").apply();
    }

    /** 与 authorization 配套的设备头，[名,值,名,值…]。 */
    public static String[] deviceHeaders(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String k : new String[]{"x-device-id", "x-device-model", "x-client-bundle-id",
                "x-client-version", "x-client-locale", "x-client-timezone-offset"}) {
            String v = header(c, k);
            if (!v.isEmpty()) {
                out.add(k);
                out.add(v);
            }
        }
        return out.toArray(new String[0]);
    }

    /** 与 authorization 配套的设备头（键名带 h_ 前缀）。 */
    public static String header(Context c, String name) {
        return Prefs.acct(c).getString("h_" + name, "");
    }

    /** 页面自己拿到的接口响应（按类型存），面板优先用它，避免自己拼请求头。 */
    public static void savePage(Context c, String url, String body) {
        String key = url.contains("get_user_summary") ? "pg_sum"
                : url.contains("by_api_key/cost") || url.contains("usage/cost") ? "pg_cost"
                : url.contains("usage/amount") ? "pg_amount" : null;
        if (key == null) return;
        Prefs.acct(c).edit()
                .putString(key, body).putLong(key + "At", System.currentTimeMillis()).apply();
    }

    public static String pageSummary(Context c) {
        return Prefs.acct(c).getString("pg_sum", "");
    }

    public static String pageCost(Context c) {
        return Prefs.acct(c).getString("pg_cost", "");
    }

    public static boolean pageFresh(Context c, long maxAgeMs) {
        long at = Math.max(
                Prefs.acct(c).getLong("pg_sumAt", 0L),
                Prefs.acct(c).getLong("pg_costAt", 0L));
        return at > 0 && System.currentTimeMillis() - at < maxAgeMs;
    }

    /** 页面自己拿到的 usage 响应（原样），用于和官网显示保持一致。 */
    public static void saveUsage(Context c, String url, String body) {
        Prefs.acct(c).edit()
                .putString("u_body", body)
                .putString("u_url", url)
                .putLong("u_at", System.currentTimeMillis())
                .apply();
    }

    public static String usageUrl(Context c) {
        return Prefs.acct(c).getString("u_url", "");
    }

    public static String usageBody(Context c) {
        return Prefs.acct(c).getString("u_body", "");
    }

    public static long usageAt(Context c) {
        return Prefs.acct(c).getLong("u_at", 0L);
    }

    public static void clear(Context c) {
        Prefs.acct(c).edit().clear().apply();
    }

    /** 会话 cookie 也加密存（旧版是明文 "k"，读取时兼容）。 */
    public static void saveCookie(Context c, String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) return;
        try {
            Cipher cip = Cipher.getInstance("AES/GCM/NoPadding");
            cip.init(Cipher.ENCRYPT_MODE, key(ALIAS));
            byte[] data = cip.doFinal(cookie.trim().getBytes(UTF8));
            Prefs.acct(c).edit()
                    .putString("k", Base64.encodeToString(data, Base64.NO_WRAP))
                    .putString("ki", Base64.encodeToString(cip.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Throwable t) { Log.ignored("AccountSession.cookie", t); }
    }

    public static String cookie(Context c) {
        SharedPreferences sp = Prefs.acct(c);
        String body = sp.getString("k", "");
        if (body.isEmpty()) return "";
        String iv = sp.getString("ki", "");
        if (iv.isEmpty()) return body;          // 旧版明文
        try {
            Cipher cip = Cipher.getInstance("AES/GCM/NoPadding");
            cip.init(Cipher.DECRYPT_MODE, key(ALIAS), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cip.doFinal(Base64.decode(body, Base64.NO_WRAP)), UTF8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 通用小密钥（API Key 等）：AES-GCM 存在账户 prefs，key 名为 sec_&lt;name&gt;。 */
    public static void saveSecret(Context c, String name, String value) {
        if (name == null || name.isEmpty()) return;
        try {
            if (value == null || value.trim().isEmpty()) {
                Prefs.acct(c).edit().remove("sec_" + name).remove("sec_" + name + "_iv").apply();
                return;
            }
            Cipher cip = Cipher.getInstance("AES/GCM/NoPadding");
            cip.init(Cipher.ENCRYPT_MODE, key(ALIAS));
            byte[] data = cip.doFinal(value.trim().getBytes(UTF8));
            Prefs.acct(c).edit()
                    .putString("sec_" + name, Base64.encodeToString(data, Base64.NO_WRAP))
                    .putString("sec_" + name + "_iv", Base64.encodeToString(cip.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Throwable t) { Log.ignored("AccountSession.secret", t); }
    }

    public static String secret(Context c, String name) {
        String body = Prefs.acct(c).getString("sec_" + name, "");
        String iv = Prefs.acct(c).getString("sec_" + name + "_iv", "");
        if (body.isEmpty() || iv.isEmpty()) return "";
        try {
            Cipher cip = Cipher.getInstance("AES/GCM/NoPadding");
            cip.init(Cipher.DECRYPT_MODE, key(ALIAS), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cip.doFinal(Base64.decode(body, Base64.NO_WRAP)), UTF8);
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clearSecret(Context c, String name) {
        Prefs.acct(c).edit().remove("sec_" + name).remove("sec_" + name + "_iv").apply();
    }

    /** API Key：优先读加密值；旧版明文读出来后就地迁移（并删掉明文）。 */
    public static String apiKey(Context c) {
        String v = secret(c, "apikey");
        if (!v.isEmpty()) return v;
        String legacy = Prefs.pet(c).getString("apikey", "");
        if (!legacy.isEmpty()) {
            saveSecret(c, "apikey", legacy);
            Prefs.pet(c).edit().remove("apikey").apply();
        }
        return legacy;
    }

    public static void saveApiKey(Context c, String value) {
        saveSecret(c, "apikey", value);
        Prefs.pet(c).edit().remove("apikey").apply();   // 明文不再保留
    }

    public static void clearApiKey(Context c) {
        clearSecret(c, "apikey");
        Prefs.pet(c).edit().remove("apikey").apply();
    }

    /** 缓存最近一次账户数据（JSON 字符串），打开面板先显示旧值。 */
    public static void saveCache(Context c, String json) {
        Prefs.acct(c).edit().putString("c", json).apply();
    }

    /** 只清缓存的展示行，不动登录态。 */
    public static void clearCache(Context c) {
        Prefs.acct(c).edit().remove("c").apply();
    }

    public static String cache(Context c) {
        return Prefs.acct(c).getString("c", "");
    }

    /** 启动时调用：旧版本密文/明文用旧别名读出来，再用新别名重加密。 */
    public static void rotateIfNeeded(Context c) {
        SharedPreferences sp = Prefs.acct(c);
        int v = sp.getInt("kv", 3);
        if (v >= KEY_VERSION) return;
        String token = token(c);
        String cookie = cookie(c);
        if (!token.isEmpty()) save(c, token, ALIAS);
        if (!cookie.isEmpty()) saveCookie(c, cookie);
        sp.edit().putInt("kv", KEY_VERSION).apply();
        Bridge.diag("acct", "key rotated to v" + KEY_VERSION);
    }

    private static SecretKey key(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }
}
