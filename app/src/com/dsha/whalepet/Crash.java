package com.dsha.whalepet;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 崩溃现场留档：写进 filesDir/last-crash.txt，下次启动上报到桥的诊断环。
 *
 * 以前闪退后什么都不剩（App 自己的 diag 只在运行时发，进程一死就没了），
 * 用户只能复述"刚才崩了"。
 */
final class Crash {

    private static final String FILE = "last-crash.txt";
    private static boolean installed;
    private static Thread.UncaughtExceptionHandler previous;

    private Crash() { }

    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        reportPrevious(app);
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Bridge.diag("crash", "handler installed");
        Thread.setDefaultUncaughtExceptionHandler((thread, err) -> {
            try {
                write(app, thread, err);
            } catch (Throwable ignored) {
                // 写盘都失败就只剩系统自己的记录
            }
            if (previous != null) previous.uncaughtException(thread, err);
        });
    }

    private static void write(Context app, Thread thread, Throwable err) throws Exception {
        StringWriter sw = new StringWriter();
        err.printStackTrace(new PrintWriter(sw));
        File f = new File(app.getFilesDir(), FILE);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(("thread=" + thread.getName() + "\n" + sw).getBytes("UTF-8"));
        }
    }

    private static void reportPrevious(Context app) {
        File f = new File(app.getFilesDir(), FILE);
        if (!f.isFile()) return;
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 4000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                Bridge.diag("crash", new String(buf, 0, n, "UTF-8").replace('\n', ' '));
            }
        } catch (Throwable ignored) {
            // 读不出来就算了
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
