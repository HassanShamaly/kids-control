package com.almurshid.kidscontrol;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** حارس دوري: كل ربع ساعة يتأكد أن خدمة المراقبة تعمل ويعيد تشغيلها إن أوقفها النظام. */
public final class Watchdog {
    static final String ACTION = "com.almurshid.kidscontrol.WATCHDOG";
    private Watchdog() { }

    public static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, BootReceiver.class).setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(c, 77, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5 * 60_000L,
                    AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
        } catch (Throwable ignored) { }
    }
}
