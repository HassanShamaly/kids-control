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

    private static long lastArm = 0L;

    /**
     * «مفتاح الرجل الميت»: الخدمة تؤجّل هذا المنبّه باستمرار وهي تعمل. إن أوقفها النظام
     * (حرارة الجهاز أثناء اللعب، توفير البطارية) يرنّ خلال دقيقة ونصف تقريباً فيعيد تشغيلها.
     */
    public static void armSoon(Context c, long delayMs) {
        long nowEl = SystemClock.elapsedRealtime();
        if (nowEl - lastArm < 25_000L) return;
        lastArm = nowEl;
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, BootReceiver.class).setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(c, 78, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = nowEl + delayMs;
            boolean exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        } catch (Throwable ignored) { }
    }

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
