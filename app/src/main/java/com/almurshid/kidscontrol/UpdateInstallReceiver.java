package com.almurshid.kidscontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/**
 * يستقبل نتيجة تثبيت التحديث:
 * - إن طلب النظام تأكيد المستخدم: يفتح شاشة التثبيت.
 * - إن نجح: لا شيء (يُعاد تشغيل التطبيق).
 * - إن مُنع التثبيت (حماية جوجل/الحظر التلقائي أو غيره): يعرض إشعاراً
 *   يفتح «إعدادات الأمان» بضغطة لإيقاف المانع مؤقتاً ثم إعادة المحاولة.
 */
public class UpdateInstallReceiver extends BroadcastReceiver {

    public static final String ACTION_STATUS = "com.almurshid.kidscontrol.INSTALL_STATUS";
    private static final String CH_ID = "kids_control_update";
    private static final int NOTIF_ID = 2002;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { ctx.startActivity(confirm); } catch (Throwable ignored) { }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                try { Toast.makeText(ctx, R.string.upd_installed, Toast.LENGTH_SHORT).show(); }
                catch (Throwable ignored) { }
                break;
            default:
                // فشل/حظر — وجّه المستخدم لإعدادات الأمان
                notifyBlocked(ctx);
                break;
        }
    }

    private void notifyBlocked(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, ctx.getString(R.string.upd_channel), NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
        }
        PendingIntent sec = PendingIntent.getActivity(ctx, 0,
                new Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(ctx, CH_ID)
                .setContentTitle(ctx.getString(R.string.upd_blocked_title))
                .setContentText(ctx.getString(R.string.upd_blocked_text))
                .setStyle(new Notification.BigTextStyle().bigText(ctx.getString(R.string.upd_blocked_text)))
                .setSmallIcon(R.drawable.ic_shield)
                .setAutoCancel(true)
                .setContentIntent(sec)
                .build();
        nm.notify(NOTIF_ID, n);
    }
}
