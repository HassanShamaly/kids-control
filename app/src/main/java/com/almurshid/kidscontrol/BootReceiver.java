package com.almurshid.kidscontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** يعيد تشغيل خدمة المراقبة بعد إعادة تشغيل الجهاز. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Watchdog.schedule(context);              // (إعادة) جدولة الحارس الدوري
        MonitorService.ensureRunning(context);   // يشغّل الخدمة إن كانت المراقبة مفعّلة (آمن من الاستثناءات)
    }
}
