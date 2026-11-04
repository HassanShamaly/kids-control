package com.almurshid.kidscontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** يعيد تشغيل خدمة المراقبة بعد إعادة تشغيل الجهاز. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        if (!prefs.enabled()) return;

        Intent svc = new Intent(context, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
