package com.dsha.whalepet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机后把小人放回桌面（仅当上次是开着状态）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(a)) {
            return;
        }
        if (!Settings.canDrawOverlays(c)) return;
        if (!Prefs.pet(c)
                .getBoolean("enabled", false)) {
            return;
        }
        try {
            c.startService(new Intent(c, PetService.class)
                    .setAction(PetService.ACTION_START));
        } catch (Throwable t) { Log.ignored("BootReceiver", t);
            // 系统限制就算了，用户手动点一下
        }
    }
}
