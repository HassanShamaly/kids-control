package com.almurshid.kidscontrol;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

/**
 * «استراحة القرآن»: أثناء الاستراحة يمكن للطفل فتح تطبيق صحبة القرآن وحده.
 * تُرفع طبقة الاستراحة ما دامت نافذة صحبة القرآن هي الظاهرة؛ أي نافذة أخرى
 * (الرئيسية، التطبيقات الأخيرة، يوتيوب…) تُنهي الإذن فتعود الاستراحة فوراً.
 */
public final class QuranPass {
    static final String URL = "https://hassanshamaly.github.io/sohba/v6/";
    private static volatile long until = 0L;
    private QuranPass() { }

    static boolean active() { return System.currentTimeMillis() < until; }
    static void start(long untilTs) { until = untilTs; }
    static void end() { until = 0L; }

    private static boolean isChrome(String pkg) {
        return pkg.equals("com.android.chrome") || pkg.startsWith("com.chrome.");
    }

    private static boolean looksLikeSohba(CharSequence s) {
        if (s == null) return false;
        String t = s.toString();
        return t.contains("صحبة") || t.contains("القرآن") || t.contains("قرآن")
                || t.toLowerCase().contains("sohba") || t.contains("hassanshamaly.github.io/sohba");
    }

    /** هل هذه النافذة هي صحبة القرآن؟ (تطبيق الويب المثبّت أو لسان مخصّص بلا شريط عنوان) */
    static boolean isSohbaWindow(Context c, String pkg, String cls, CharSequence title) {
        if (pkg == null) return false;
        if (pkg.startsWith("org.chromium.webapk")) {
            try {
                PackageManager pm = c.getPackageManager();
                return looksLikeSohba(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (Throwable t) { return false; }
        }
        if (isChrome(pkg) && cls != null
                && (cls.contains(".webapps.") || cls.contains("customtabs") || cls.contains("WebApk") || cls.contains("Webapp"))) {
            // عنوان النافذة إن وُجد يجب أن يكون صحبة القرآن (يمنع تطبيقات ويب أخرى مثبّتة)
            return title == null || title.toString().trim().isEmpty() || looksLikeSohba(title);
        }
        return false;
    }

    /** نية فتح صحبة القرآن: التطبيق المثبّت إن وُجد، وإلا لسان كروم مخصّص (بلا شريط عنوان). */
    static Intent launchIntent(Context c) {
        Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
        PackageManager pm = c.getPackageManager();
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(v, 0)) {
                if (ri.activityInfo != null && ri.activityInfo.packageName.startsWith("org.chromium.webapk")) {
                    v.setPackage(ri.activityInfo.packageName);
                    return v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            }
        } catch (Throwable ignored) { }
        try {
            pm.getPackageInfo("com.android.chrome", 0);
            v.setPackage("com.android.chrome");
        } catch (Throwable t) { return null; }   // بلا كروم لا نستطيع ضمان نافذة صحبة القرآن وحدها
        Bundle b = new Bundle();
        b.putBinder("android.support.customtabs.extra.SESSION", null);   // لسان مخصّص: لا يمكن كتابة رابط آخر
        v.putExtras(b);
        return v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
