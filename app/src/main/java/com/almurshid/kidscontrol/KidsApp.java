package com.almurshid.kidscontrol;

import android.app.Application;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * يلتقط أي انهيار في أي خيط ويحفظ سببه، ليُعرَض لولي الأمر عند فتح التطبيق.
 * لا يمنع الانهيار، لكنه يكشف جذره بدل التخمين.
 */
public class KidsApp extends Application {

    public static final String CRASH_PREFS = "kids_crash";
    public static final String K_CRASH = "last_crash";

    @Override
    public void onCreate() {
        super.onCreate();
        Watchdog.schedule(this);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String trace = sw.toString();
                if (trace.length() > 4000) trace = trace.substring(0, 4000);
                String msg = "thread=" + thread.getName() + " @ "
                        + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                              .format(new java.util.Date()) + "\n" + trace;
                SharedPreferences sp = getSharedPreferences(CRASH_PREFS, MODE_PRIVATE);
                sp.edit().putString(K_CRASH, msg).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }
}
