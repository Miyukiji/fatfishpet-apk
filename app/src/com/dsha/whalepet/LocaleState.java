package com.dsha.whalepet;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 语言切换的副作用：清掉存了本地化文字的缓存（天气、本机行、账户行）。
 *
 * 单独一个类是为了让 Txt 保持“只有 Context”，JVM 单测才能编译。
 */
final class LocaleState {

    private LocaleState() { }

    static void sync(Context c) {
        try {
            String lang = c.getResources().getConfiguration()
                    .getLocales().get(0).getLanguage();
            SharedPreferences sp = Prefs.pet(c);
            String last = sp.getString("uiLang", "");
            if (lang.equals(last)) return;
            sp.edit().putString("uiLang", lang)
                    .remove("wLine").putLong("wAt", 0L)
                    .remove("localLine").putLong("localAt", 0L)
                    .putLong("acctAt", 0L)
                    .apply();
            AccountSession.clearCache(c);
            Bridge.diag("locale", "uiLang=" + last + "->" + lang + " caches cleared");
        } catch (Throwable t) {
            Log.ignored("LocaleState", t);
        }
    }
}
