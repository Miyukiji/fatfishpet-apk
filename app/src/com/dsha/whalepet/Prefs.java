package com.dsha.whalepet;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 偏好文件的唯一入口。
 *
 * 之前 35 处调用散在 5 个文件里，每处都要重复写文件名和 MODE_PRIVATE；
 * 文件名一旦改就得全文搜。现在只有这里出现字面量。
 */
final class Prefs {

    /** 桌宠主配置（尺寸、位置、三击目标等）。 */
    static final String PET = "pet";
    /** 账户会话与加密后的凭据。 */
    static final String ACCT = "acct3";

    private Prefs() { }

    static SharedPreferences pet(Context c) {
        return c.getSharedPreferences(PET, Context.MODE_PRIVATE);
    }

    static SharedPreferences acct(Context c) {
        return c.getSharedPreferences(ACCT, Context.MODE_PRIVATE);
    }
}
