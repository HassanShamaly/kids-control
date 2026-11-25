package com.almurshid.kidscontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

    // ================= وضع القفل المشدّد =================
    // أثناء شاشة القفل تتوسع الخدمة لتراقب كل التطبيقات (لا الإعدادات فقط)، فأي تطبيق يُفتح
    // من لوحة الحافة أو التطبيقات الأخيرة يُغلق فوراً. يعود النطاق الضيق عند الفتح (لا تعارض مع البنوك).
    private static volatile boolean lockdown = false;
    private volatile Boolean appliedScope = null;   // النطاق المطبّق فعلاً (null = لم يُطبّق بعد)
    private String[] origPkgs;                       // نطاق الإعدادات الأصلي من ملف الخدمة
    private int origEvents = -1;                     // أنواع الأحداث الأصلية
    private final Handler main = new Handler(Looper.getMainLooper());
    private long ignoreUntil = 0L;
    private String homePkg;

    /** يُستدعى من خدمة المراقبة في كل دورة بحالة القفل الحالية. */
    public static void setLockdown(boolean on) {
        lockdown = on;
        KidsAccessibilityService s = instance;
        if (s != null && (s.appliedScope == null || s.appliedScope != on)) s.main.post(s::applyScope);
    }

    private void applyScope() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            boolean on = lockdown;
            info.packageNames = on ? null : origPkgs;
            // أثناء القفل: حدث فتح النوافذ فقط (لا سيل «تغيّر المحتوى» من كل التطبيقات = حرارة)
            if (origEvents != -1)
                info.eventTypes = on ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : origEvents;
            setServiceInfo(info);
            appliedScope = on;
        } catch (Throwable ignored) { }
    }

    private boolean isLauncher(String pkg) {
        if (homePkg == null) {
            try {
                ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
                homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
            } catch (Throwable t) { homePkg = ""; }
        }
        return pkg.equals(homePkg) || pkg.contains("launcher");
    }

    /** الشاشة مضاءة والجوال مفتوح (ليس على شاشة القفل الأصلية). */
    private boolean screenInUse() {
        try {
            android.os.PowerManager pw = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pw != null && !pw.isInteractive()) return false;
            android.app.KeyguardManager kg = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (kg != null && kg.isKeyguardLocked()) return false;
        } catch (Throwable ignored) { }
        return true;
    }

    private static boolean isCallPkg(String pkg) {
        return pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("telecom")
                || pkg.equals("com.android.phone") || pkg.contains("emergency");
    }

    private static boolean isEdgePanel(String pkg) {
        return pkg.contains("cocktailbar") || pkg.contains("appsedge") || pkg.contains("edgepanel")
                || pkg.contains("edgescreen") || pkg.contains("edgelighting");
    }

    /** أثناء القفل: أي نافذة تُفتح خلف/فوق الطبقة تُغلق (لوحة الحافة، التطبيقات الأخيرة، أي تطبيق). */
    private void handleLocked(String pkg, String cls) {
        // الشاشة مطفأة أو قفل الجوال الأصلي ظاهر: لا نفعل شيئاً. أي «رجوع/رئيسية» هنا كان
        // يوقظ الشاشة (أحداث شاشة العرض الدائم AOD) فتظل تفتح وتطفئ طوال الاستراحة.
        if (!screenInUse()) return;
        if (pkg.contains("aod") || pkg.contains("keyguard") || pkg.contains("lockscreen")
                || pkg.contains("screensaver") || pkg.contains("dream")) return;
        if (pkg.equals(getPackageName()) || isCallPkg(pkg)) return;
        if (pkg.equals("com.android.systemui") || pkg.equals("android")) return;   // الإشعارات، قائمة التشغيل، الطوارئ
        if (pkg.contains("packageinstaller") && prefs != null && prefs.installActive()) return;
        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard")) return;
        long now = SystemClock.uptimeMillis();
        if (now < ignoreUntil) return;
        if (isEdgePanel(pkg)) {
            ignoreUntil = now + 1200L;
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        if (isLauncher(pkg)) {
            // الرئيسية الثابتة لا تُطلق أحداثاً؛ الحدث هنا = فتح «التطبيقات الأخيرة» أو درج التطبيقات
            ignoreUntil = now + 1200L;
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        if (cls == null || !isActivity(pkg, cls)) return;
        ignoreUntil = now + 1200L;
        performGlobalAction(GLOBAL_ACTION_BACK);
        performGlobalAction(GLOBAL_ACTION_HOME);
        final String victim = pkg;
        main.postDelayed(() -> MonitorService.killBg(this, victim), 1500L);   // أوقف الفيديو نهائياً
    }

    /** التطبيق المفتوح حسب خدمة الحماية، أو null إن لم تكن تعمل. */
    public static String currentForeground() {
        return instance != null ? foregroundPkg : null;
    }

    /**
     * يوقف خدمة الحماية مؤقتاً (وضع البنوك): تطبيقات البنوك ترفض العمل مع خدمة
     * إمكانية وصول مفعّلة. يعيدها ولي الأمر يدوياً من إعدادات إمكانية الوصول.
     * حدود الوقت والقفل تستمران لأنهما يعتمدان على سجل الاستخدام والطبقة لا على هذه الخدمة.
     */
    public static boolean pauseForBanking() {
        KidsAccessibilityService s = instance;
        if (s == null) return false;
        try { s.disableSelf(); return true; } catch (Throwable t) { return false; }
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
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) { origPkgs = info.packageNames; origEvents = info.eventTypes; }
        } catch (Throwable ignored) { }
        appliedScope = null;
        if (lockdown) applyScope();
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
                if (lockdown) { handleLocked(pkg, cls != null ? cls.toString() : null); return; }
            }
            if (lockdown && !GUARDED.contains(pkg)) return;   // أثناء القفل: لا نعالج محتوى بقية التطبيقات

            boolean installer = pkg.contains("packageinstaller");
            // أثناء نافذة تحديث التطبيق لا نحرس شاشة التثبيت، فلا يتعارض القفل مع التحديث
            if (installer && prefs != null && prefs.installActive()) {
                return;
            }
            if (GUARDED.contains(pkg) || installer
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
