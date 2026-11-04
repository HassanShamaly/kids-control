package com.almurshid.kidscontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * خدمة الحماية (إمكانية الوصول):
 * 1) تعرف التطبيق المفتوح بدقة لحظة بلحظة.
 * 2) تمنع الطفل من فتح صفحات التطبيق في الإعدادات (الحذف، الإيقاف الإجباري،
 *    سحب الصلاحيات، إلغاء مسؤول الجهاز) — ترجعه للشاشة الرئيسية،
 *    إلا إذا كان ولي الأمر قد دخل للتطبيق بالرقم السري والبصمة قبل قليل.
 */
public class KidsAccessibilityService extends AccessibilityService {

    private static volatile KidsAccessibilityService instance;
    private static volatile String foregroundPkg;

    /** حزم الإعدادات وشاشات الحذف والصلاحيات التي نحرسها. */
    private static final Set<String> GUARDED = new HashSet<>();
    static {
        String[] g = {
            "com.android.settings", "com.samsung.android.settings", "com.samsung.accessibility",
            "com.samsung.android.lool", "com.samsung.android.sm.devicesecurity",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.google.android.apps.wellbeing",
            "com.samsung.android.forest",
        };
        for (String s : g) GUARDED.add(s);
    }

    private Prefs prefs;
    private PackageManager pm;
    private final Map<String, Boolean> activityCache = new HashMap<>();
    private long lastGuardCheck = 0L;
    private long lastKick = 0L;

    public static boolean isRunning() { return instance != null; }

    /** التطبيق المفتوح حسب خدمة الحماية، أو null إن لم تكن تعمل. */
    public static String currentForeground() {
        return instance != null ? foregroundPkg : null;
    }

    /** يخرج إلى الشاشة الرئيسية (يُستخدم عند القفل). */
    public static void goHome() {
        KidsAccessibilityService s = instance;
        if (s != null) {
            try { s.performGlobalAction(GLOBAL_ACTION_HOME); } catch (Throwable ignored) { }
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = new Prefs(this);
        pm = getPackageManager();
        MonitorService.ensureRunning(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        try {
            if (e == null || e.getPackageName() == null) return;
            String pkg = e.getPackageName().toString();
            int type = e.getEventType();

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                CharSequence cls = e.getClassName();
                if (cls != null && isActivity(pkg, cls.toString())) {
                    foregroundPkg = pkg;
                }
            }

            if (GUARDED.contains(pkg) || pkg.contains("packageinstaller")
                    || pkg.contains("permissioncontroller")) {
                guard(type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            }
        } catch (Throwable ignored) { }
    }

    /** هل هذه نافذة نشاط حقيقي (وليست لوحة مفاتيح أو نافذة منبثقة)؟ */
    private boolean isActivity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean b = activityCache.get(key);
        if (b != null) return b;
        boolean r;
        try {
            pm.getActivityInfo(new ComponentName(pkg, cls), 0);
            r = true;
        } catch (Exception ex) {
            r = false;
        }
        activityCache.put(key, r);
        return r;
    }

    private void guard(boolean force) {
        if (prefs == null || prefs.parentModeActive()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastGuardCheck < 400L) return;
        lastGuardCheck = now;

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return;
            List<AccessibilityNodeInfo> hits =
                    root.findAccessibilityNodeInfosByText(getString(R.string.app_name));
            if (hits != null && !hits.isEmpty()) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                performGlobalAction(GLOBAL_ACTION_HOME);
                if (now - lastKick > 3000L) {
                    lastKick = now;
                    Toast.makeText(this, R.string.guard_blocked, Toast.LENGTH_LONG).show();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
