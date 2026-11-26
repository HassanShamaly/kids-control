package com.almurshid.kidscontrol;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Set;

/**
 * الخدمة التي تراقب التطبيق المفتوح باستمرار. عند تجاوز المدة المحددة
 * للفيديو أو الألعاب تعرض طبقة قفل تغطي الشاشة ولا تُفتح إلا ببصمة ولي الأمر.
 *
 * ملاحظات تقنية:
 * - الفحص (فحص الاستخدام، جلسات الوسائط، فحص ملفات التطبيقات) يجري على خيط
 *   خلفي حتى لا يتجمّد التطبيق (كان سبب رسالة «خلل» من أندرويد).
 * - عمليات الطبقة (overlay) تجري على الخيط الرئيسي حصراً.
 * - الإيقاف لا يقتل الخدمة: تبقى قائمة وتعرض إشعاراً فيه زر «تشغيل».
 */
public class MonitorService extends Service {

    public static final String ACTION_UNLOCK_OK = "com.almurshid.kidscontrol.UNLOCK_OK";
    public static final String ACTION_STOP      = "com.almurshid.kidscontrol.STOP";   // إيقاف المراقبة (تبقى الخدمة)
    public static final String ACTION_START     = "com.almurshid.kidscontrol.START";  // تشغيل المراقبة
    public static final String ACTION_FULL_STOP = "com.almurshid.kidscontrol.FULL_STOP"; // إغلاق تام
    public static final String ACTION_SUPPRESS_OVERLAY = "com.almurshid.kidscontrol.SUPPRESS_OVERLAY"; // أخفِ الطبقة مؤقتاً لإدخال الرقم
    public static final String ACTION_RESHOW_OVERLAY   = "com.almurshid.kidscontrol.RESHOW_OVERLAY";
    public static final String ACTION_EMERGENCY        = "com.almurshid.kidscontrol.EMERGENCY";  // مدخل الطوارئ من الإشعار
    public static final String ACTION_FORCE_BREAK      = "com.almurshid.kidscontrol.FORCE_BREAK"; // منع فوري (لحظي = 0)
    public static final String ACTION_REMOTE_UNLOCK    = "com.almurshid.kidscontrol.REMOTE_UNLOCK"; // فتح القفل عن بُعد من جوال ولي الأمر

    private static final String CH_ID = "kids_control_status";
    private static final int NOTIF_ID = 1001;
    private static final long POLL_MS = 2000L;
    // توفير البطارية والحرارة: والشاشة مطفأة لا يوجد ما يُراقَب، فتُبطَّأ الدورة كثيراً
    private static final long IDLE_POLL_MS = 30_000L;
    private volatile boolean screenOnNow = true;
    private long lastAliveSave = 0L;

    /** عند إضاءة الشاشة/فتح الجوال: دورة فورية ومزامنة فورية (بدل انتظار الدورة البطيئة). */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (bgHandler == null || loop == null) return;
            screenOnNow = true;
            RemoteSync.pullSoon();
            bgHandler.post(() -> { lastPollTs = System.currentTimeMillis(); });
            bgHandler.removeCallbacks(loop);
            bgHandler.post(loop);
        }
    };

    private static volatile boolean running = false;
    public static boolean isActive() { return running; }
    /** آخر عطل في حلقة المراقبة (null = لا أعطال). */
    public static volatile String lastError = null;

    private Handler bgHandler;   // خيط الفحص
    private Handler uiHandler;    // الخيط الرئيسي (الطبقة)
    private HandlerThread bgThread;
    private Runnable loop;

    private Prefs prefs;
    private AppClassifier classifier;
    private UsageStatsManager usm;
    private WindowManager wm;
    private AudioManager am;
    private PowerManager power;
    private KeyguardManager keyguard;
    private AudioFocusRequest focusReq;

    // حالة العدّ (تُقرأ وتُكتب على الخيط الخلفي)
    private final long[] usedMs = new long[3];
    private int currentCat = AppClassifier.CAT_NONE;
    private long lastPollTs = 0L;
    private long lastWallTs = 0L, lastElapsedTs = 0L;   // لكشف تغيير ساعة الجوال
    private long nightScreenMs = 0L, lastNightSaveTs = 0L; // زمن الشاشة العام (أي تطبيق)
    private long screenIdleSince = 0L;                        // منذ متى الجوال غير مستخدم
    private long lastMonitoredTs = 0L;
    private volatile boolean locked = false;
    private int lockedCat = 0;
    private long graceUntil = 0L;
    private long lastSaveTs = 0L;

    // الاستراحة الإجبارية والجلسة
    private long periodMs = 0L;      // رصيد موحّد (فيديو+ألعاب) للفترة الحالية — يُصفَّر عند الفتح
    private long sessionMs = 0L;     // زمن متواصل منذ آخر استراحة/تصفير
    private int unlockCount = 0;     // عدد التبصيمات للمواصلة في الجلسة
    private long breakUntil = 0L;    // نهاية الاستراحة الإجبارية (0 = لا استراحة)
    private long overlaySuppressUntil = 0L; // لم يعد يُضبط أبداً: الطبقة لا تُخفى أثناء القفل

    private String lastFg = null;
    private long lastEventQueryTs = 0L;
    private long lastNotifTs = 0L;
    private String lastDiag = "";

    private volatile boolean overlayVisible = false;
    private View overlay;        // يُلمَس على الخيط الرئيسي فقط
    private TextView overlayMsg;  // للعدّاد التنازلي أثناء الاستراحة

    private final BroadcastReceiver unlockReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            if (ACTION_UNLOCK_OK.equals(i.getAction())) {
                final boolean breakOverride = i.getBooleanExtra("break_override", false);
                if (bgHandler != null) bgHandler.post(() -> {
                    if (breakOverride) endBreak();
                    else onUnlocked();
                });
            }
        }
    };

    /** يشغّل الخدمة إن كانت المراقبة مفعّلة. */
    public static void ensureRunning(Context c) {
        try {
            if (!new Prefs(c).enabled()) return;
            Intent svc = new Intent(c, MonitorService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(svc);
            else c.startService(svc);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        classifier = new AppClassifier(this);
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        uiHandler = new Handler(Looper.getMainLooper());
        bgThread = new HandlerThread("kc-monitor");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(unlockReceiver, new IntentFilter(ACTION_UNLOCK_OK));
        try {
            IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_ON);
            sf.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, sf);
        } catch (Throwable ignored) { }

        try {
            NotificationManager nm0 = getSystemService(NotificationManager.class);
            if (nm0 != null) nm0.deleteNotificationChannel("kids_control_monitor");
        } catch (Throwable ignored) { }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.n_running),
                getString(R.string.notif_text), null, ACT_NONE));
        running = true;

        usedMs[AppClassifier.CAT_YT] = prefs.usedYt();
        usedMs[AppClassifier.CAT_GAME] = prefs.usedGame();
        lastMonitoredTs = prefs.lastMonitored();
        currentCat = prefs.currentCat();
        periodMs = prefs.periodMs();
        nightScreenMs = prefs.screenUsedMs();
        sessionMs = prefs.sessionMs();
        unlockCount = prefs.unlockCount();
        breakUntil = prefs.breakUntil();
        if (prefs.locked()) { locked = true; lockedCat = prefs.lockedCat(); }

        // أُعيد تشغيل الخدمة بعد أن أوقفها النظام (مثلاً حرارة الجهاز أثناء اللعب) والطفل كان
        // في وقت محسوب: مدة التوقف تُحتسب عليه، فيُقفل فوراً إن كان قد تجاوز وقته.
        long alive = prefs.aliveTs();
        long gapNow = System.currentTimeMillis();
        long gap = gapNow - alive;
        if (alive > 0 && gap > 20_000L && gap < 6 * 3_600_000L && !locked
                && currentCat != AppClassifier.CAT_NONE && prefs.enabled()) {
            periodMs += gap;
            sessionMs += gap;
            if (currentCat >= 0 && currentCat < usedMs.length) usedMs[currentCat] += gap;
            lastMonitoredTs = gapNow;
            prefs.setPeriodMs(periodMs);
            prefs.setSessionMs(sessionMs);
            prefs.saveUsage(usedMs[AppClassifier.CAT_YT], usedMs[AppClassifier.CAT_GAME], lastMonitoredTs, currentCat);
            lastError = "restarted after " + (gap / 1000L) + "s stop";
        }
        prefs.setAliveTs(gapNow);

        loop = new Runnable() {
            @Override public void run() {
                try { poll(); }
                catch (Throwable t) {   // لا نبتلع العطل بصمت: يُبلَّغ به جهاز ولي الأمر
                    String m = String.valueOf(t.getMessage());
                    lastError = t.getClass().getSimpleName() + ": " + (m.length() > 80 ? m.substring(0, 80) : m);
                }
                bgHandler.postDelayed(this, screenOnNow ? POLL_MS : IDLE_POLL_MS);
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            // إيقاف المراقبة دون قتل الخدمة: تبقى لعرض الحالة وزر التشغيل
            prefs.setEnabled(false);
            if (bgHandler != null) bgHandler.post(() -> { onUnlocked(); showStoppedNotification(); });
            return START_STICKY;
        }
        if (ACTION_FULL_STOP.equals(action)) {
            // إغلاق تام: توقف المراقبة ويُزال الإشعار وتُغلق الخدمة بالكامل
            prefs.setEnabled(false);
            prefs.setResumeAt(0);
            prefs.setLocked(false, 0);
            if (bgHandler != null) bgHandler.post(() -> {
                locked = false; breakUntil = 0L; overlaySuppressUntil = 0L;
                postRemoveOverlay();
            });
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SUPPRESS_OVERLAY.equals(action)) {
            // أُلغي: إخفاء طبقة القفل مؤقتاً كان ثغرة (إلغاء/رجوع = وقت مجاني)
            return START_STICKY;
        }
        if (ACTION_RESHOW_OVERLAY.equals(action)) {
            if (bgHandler != null) bgHandler.post(() -> {
                overlaySuppressUntil = 0L;
                if (locked && !overlayVisible && !QuranPass.active()) postShowOverlay(lockedCat);
            });
            return START_STICKY;
        }
        if (ACTION_EMERGENCY.equals(action)) {
            uiHandler.post(this::openPinPanel);
            return START_STICKY;
        }
        if (ACTION_FORCE_BREAK.equals(action)) {
            if (bgHandler != null) bgHandler.post(this::triggerForcedBreak);
            return START_STICKY;
        }
        if (ACTION_REMOTE_UNLOCK.equals(action)) {
            // فتح القفل عن بُعد من جوال ولي الأمر (ينهي الاستراحة أو يمنح جلسة جديدة)
            if (bgHandler != null) bgHandler.post(() -> {
                if (breakUntil > System.currentTimeMillis()) endBreak();
                else onUnlocked();
            });
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            prefs.setEnabled(true);
            prefs.setResumeAt(0);
        }
        if (bgHandler != null) {
            bgHandler.post(() -> { lastPollTs = System.currentTimeMillis(); lastNotifTs = 0L; });
            bgHandler.removeCallbacks(loop);
            bgHandler.post(loop);
        }
        return START_STICKY;
    }

    // ================= حلقة الفحص (خيط خلفي) =================
    private void poll() {
        long now = System.currentTimeMillis();
        long delta = now - lastPollTs;
        if (delta < 0 || delta > 60_000L) delta = POLL_MS;
        lastPollTs = now;
        screenOnNow = power == null || power.isInteractive();
        if (now - lastAliveSave >= 15_000L) { lastAliveSave = now; prefs.setAliveTs(now); }
        Watchdog.armSoon(this, screenOnNow ? 90_000L : 120_000L);   // يعيد التشغيل إن توقفت الخدمة
        KidsAccessibilityService.setLockdown(locked && screenOnNow);   // الشاشة مطفأة: لا مراقبة مشدّدة   // أثناء القفل: مراقبة مشدّدة لكل التطبيقات

        // حماية من تغيير ساعة الجوال: أي قفزة في الساعة تُزاح بها نهاية الاستراحة، فلا تنتهي مبكراً
        long el = android.os.SystemClock.elapsedRealtime();
        if (lastWallTs != 0L) {
            long jump = (now - lastWallTs) - (el - lastElapsedTs);
            if (Math.abs(jump) > 30_000L) {
                if (breakUntil > 0L) { breakUntil += jump; prefs.setBreakUntil(breakUntil); }
                long iu = prefs.instantUntil();
                if (iu > 0L) prefs.setInstantUntil(iu + jump);   // واللحظي كذلك
            }
        }
        lastWallTs = now;
        lastElapsedTs = el;

        // جهاز الطفل: اسحب إعدادات ولي الأمر دورياً وطبّقها
        RemoteSync.pullIfDue(this, screenOnNow ? 60_000L : 15 * 60_000L);
        // انتهاء الإعداد اللحظي ← العودة للإعداد الافتراضي
        ConfigApplier.checkInstantExpiry(prefs, now);

        // موقوف عن بُعد من جوال ولي الأمر: لا قفل ولا احتساب، وتبقى المزامنة فقط لاستقبال أمر التشغيل
        if (prefs.remoteOff()) {
            if (breakUntil > now) endBreak();
            else if (locked) onUnlocked();
            currentCat = AppClassifier.CAT_NONE;
            updateNotificationRemoteOff();
            return;
        }

        // المراقبة متوقفة: عُد تلقائياً عند حلول الموعد، وإلا اعرض إشعار الحالة
        if (!prefs.enabled()) {
            long rAt = prefs.resumeAt();
            if (rAt > 0 && now >= rAt) {
                prefs.setEnabled(true);
                prefs.setResumeAt(0);
                lastNotifTs = 0L;
                // ويكمل إلى المراقبة العادية
            } else {
                if (locked) onUnlocked();
                showStoppedNotification();
                return;
            }
        }

        // جدول التعطيل
        if (prefs.inPauseWindow()) {
            if (locked) onUnlocked();
            currentCat = AppClassifier.CAT_NONE;
            updateNotificationPaused();
            return;
        }

        // العودة التلقائية للإعداد الافتراضي كل مدة محددة
        maybeRevertDefaults(now);

        // أثناء القفل
        if (locked) {
            boolean onBreak = breakUntil > now;
            if (!onBreak && breakUntil > 0) { endBreakToFingerprint(); return; } // انتهى العدّاد → تُطلب البصمة
            if (QuranPass.active()) {
                // استراحة القرآن جارية: لا طبقة ولا إيقاف للصوت (التلاوة تعمل). الحارس يعيد الطبقة عند الخروج.
                if (!KidsAccessibilityService.isRunning()) { QuranPass.end(); }
                else { if (onBreak) updateOverlayBreak(now); return; }
            }
            if (!screenOnNow) {
                // الشاشة مطفأة: لا تحديث للعدّاد ولا للإشعار (لا أحد يراه)؛ فقط أوقف أي صوت يعمل
                if (am != null && am.isMusicActive()) { pauseMedia(); KidsNotificationListener.pauseAll(this); }
                return;
            }
            boolean suppressed = now < overlaySuppressUntil; // إدخال رقم سري جارٍ
            if (!overlayVisible && !suppressed) { postShowOverlay(lockedCat); }
            if (onBreak) updateOverlayBreak(now); else if (!suppressed) updateNotificationLocked();
            Set<String> playing = KidsNotificationListener.playingPackages(this);
            if ((playing != null && !playing.isEmpty()) || am.isMusicActive()) {
                pauseMedia();
                KidsNotificationListener.pauseAll(this);   // كل جلسة وسائط مباشرة
                if (playing != null) for (String p : playing) killBg(this, p);
            }
            return;
        }

        boolean screenOn = screenOnNow;
        // الشاشة مطفأة: لا حاجة لقراءة سجل الاستخدام الآن (يُقرأ ما فات عند إضاءتها)
        String fgUsage = screenOn ? usageForeground(now) : null;
        String fgA11y = KidsAccessibilityService.currentForeground();
        boolean deviceLocked = keyguard != null && keyguard.isKeyguardLocked();
        boolean active = screenOn && !deviceLocked;

        // حد وقت الشاشة العام (مستقل): أي استخدام للجوال في أي تطبيق يُحتسب، وبعد المدة استراحة إجبارية.
        // يبدأ العدّ من جديد إذا ابتعد الطفل عن الجوال (الشاشة مطفأة) مدة الاستراحة كاملة.
        if (prefs.screenOn()) {
            String f0 = fgA11y != null ? fgA11y : fgUsage;
            boolean call = f0 != null && (f0.contains("dialer") || f0.contains("incallui") || f0.contains("telecom"));
            long restMs = Math.max(1, prefs.breakMinutes()) * 60_000L;
            if (active) {
                screenIdleSince = 0L;
                if (!call && now > graceUntil) {
                    nightScreenMs += delta;
                    if (now - lastNightSaveTs > 20_000L) { lastNightSaveTs = now; prefs.setScreenUsedMs(nightScreenMs); }
                    if (nightScreenMs >= Math.max(1, prefs.screenMin()) * 60_000L) { triggerNightBreak(); return; }
                }
            } else {
                if (screenIdleSince == 0L) screenIdleSince = now;
                else if (nightScreenMs != 0L && now - screenIdleSince >= restMs) {
                    nightScreenMs = 0L;
                    prefs.setScreenUsedMs(0L);
                }
            }
        } else if (nightScreenMs != 0L) {
            nightScreenMs = 0L;
            prefs.setScreenUsedMs(0L);
        }

        Set<String> playing = active ? KidsNotificationListener.playingPackages(this) : null;
        boolean musicActive = am != null && am.isMusicActive();

        String fg = null;
        int cat = AppClassifier.CAT_NONE;
        if (active) {
            fg = (fgA11y != null) ? fgA11y : fgUsage;
            int cA = classifier.categoryOf(fgA11y, playing, musicActive);
            int cU = classifier.categoryOf(fgUsage, playing, musicActive);
            if (cA != AppClassifier.CAT_NONE) { cat = cA; fg = fgA11y; }
            else if (cU != AppClassifier.CAT_NONE) { cat = cU; fg = fgUsage; }
            else {
                String vp = classifier.videoPlaying(playing);
                if (vp != null) { cat = AppClassifier.CAT_YT; fg = vp; }
            }
        }
        lastDiag = "a11y=" + shortPkg(fgA11y) + " usage=" + shortPkg(fgUsage)
                + " media=" + (playing == null ? "n/a" : playing.isEmpty() ? "-" : shortPkg(playing.iterator().next()));

        boolean monitored = (cat == AppClassifier.CAT_YT && prefs.ytOn())
                || (cat == AppClassifier.CAT_GAME && prefs.gameOn());

        if (monitored) {
            currentCat = cat;
            usedMs[cat] += delta;    // للعرض فقط (تفصيل الفيديو/الألعاب)
            periodMs += delta;       // الرصيد الموحّد: الفيديو والألعاب معاً (يمنع مضاعفة الوقت بالتبديل)
            sessionMs += delta;
            lastMonitoredTs = now;
            // الحد يُطبَّق على الرصيد الموحّد، فلا ينفع التبديل بين الفيديو والألعاب
            long limitMs = prefs.effectiveLimit(cat == AppClassifier.CAT_YT) * 60_000L;
            if (limitMs > 0 && periodMs >= limitMs && now > graceUntil) {
                triggerLock(cat);
            }
        } else {
            long cooldown = prefs.cooldownSec() * 1000L;
            if (currentCat != AppClassifier.CAT_NONE && (now - lastMonitoredTs) > cooldown) {
                // ابتعد الطفل فترة كافية: جلسة جديدة تماماً
                usedMs[AppClassifier.CAT_YT] = 0;
                usedMs[AppClassifier.CAT_GAME] = 0;
                periodMs = 0;
                sessionMs = 0;
                unlockCount = 0;
                currentCat = AppClassifier.CAT_NONE;
                saveState(now, true);
            }
        }
        if (monitored) saveState(now, false);
        if (screenOn) updateNotification(monitored ? cat : AppClassifier.CAT_NONE, fg, cat, now);
    }

    private void saveState(long now, boolean force) {
        if (!force && now - lastSaveTs < 10_000L) return;
        lastSaveTs = now;
        prefs.saveUsage(usedMs[AppClassifier.CAT_YT], usedMs[AppClassifier.CAT_GAME],
                lastMonitoredTs, currentCat);
        prefs.setPeriodMs(periodMs);
        prefs.setSessionMs(sessionMs);
        prefs.setUnlockCount(unlockCount);
        prefs.setBreakUntil(breakUntil);
    }

    private static String shortPkg(String p) {
        if (p == null) return "-";
        int i = p.lastIndexOf('.');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private String usageForeground(long now) {
        try {
            long from = (lastEventQueryTs == 0L) ? now - 3_600_000L : lastEventQueryTs - 2_000L;
            UsageEvents ev = usm.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastFg = e.getPackageName();
                } else if (t == 16 /* SCREEN_NON_INTERACTIVE */) {
                    lastFg = null;
                }
            }
            lastEventQueryTs = now;
        } catch (Throwable ignored) { }
        return lastFg;
    }

    private final java.util.Map<String, String> labelCache = new java.util.HashMap<>();

    private String appLabel(String pkg) {
        if (pkg == null) return getString(R.string.n_screen_off);
        String l = labelCache.get(pkg);
        if (l != null) return l;
        try {
            l = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            l = pkg;
        }
        labelCache.put(pkg, l);
        return l;
    }

    // ================= الإشعارات =================
    private void updateNotification(int countingCat, String fg, int detectedCat, long now) {
        if (now - lastNotifTs < 5_000L) return;
        lastNotifTs = now;

        String vName = getString(R.string.cat_video);
        String gName = getString(R.string.cat_games);
        // الرصيد موحّد (فيديو + ألعاب) بحسب حد الفئة الحالية
        long usedSec = periodMs / 1000L;
        long limSec  = prefs.effectiveLimit(countingCat == AppClassifier.CAT_GAME ? false : true) * 60L;
        long ytUsed = usedMs[AppClassifier.CAT_YT] / 1000L;
        long gUsed  = usedMs[AppClassifier.CAT_GAME] / 1000L;

        String comboName = getString(R.string.cat_combo);
        String title;
        if (countingCat == AppClassifier.CAT_YT || countingCat == AppClassifier.CAT_GAME) {
            title = getString(R.string.n_counting, comboName, fmt(Math.max(0, limSec - usedSec)));
        } else {
            title = getString(R.string.n_running);
        }

        String comboLine = comboName + ": " + fmt(usedSec) + " / " + fmt(limSec);
        String breakdown = vName + " " + fmt(ytUsed) + " · " + gName + " " + fmt(gUsed);
        String catName = detectedCat == AppClassifier.CAT_YT ? vName
                : detectedCat == AppClassifier.CAT_GAME ? gName
                : getString(R.string.n_cat_none);

        StringBuilder big = new StringBuilder();
        big.append(comboLine).append('\n').append(breakdown).append('\n')
           .append(getString(R.string.n_app, appLabel(fg), catName))
           .append('\n').append(lastDiag);
        if (!KidsAccessibilityService.isRunning()) {
            big.append('\n').append("⚠ ").append(getString(R.string.n_warn_a11y));
        }
        if (KidsNotificationListener.playingPackages(this) == null) {
            big.append('\n').append("⚠ ").append(getString(R.string.n_warn_notif));
        }

        notify(buildNotification(title, comboLine, big.toString(), ACT_STOP));
    }

    private void showStoppedNotification() {
        if (System.currentTimeMillis() - lastNotifTs < 30_000L) return;
        lastNotifTs = System.currentTimeMillis();
        long rAt = prefs.resumeAt();
        String sub = rAt > System.currentTimeMillis()
                ? getString(R.string.n_stopped_resume, clock(rAt))
                : getString(R.string.n_stopped_sub);
        notify(buildNotification(getString(R.string.n_stopped), sub, null, ACT_START));
    }

    private static String clock(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(java.util.Locale.US, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    private void updateNotificationRemoteOff() {
        // كان يُعاد نشر الإشعار نفسه كل ثانيتين بلا توقف (حمل وحرارة): مرة كل 30 ثانية تكفي
        if (System.currentTimeMillis() - lastNotifTs < 30_000L) return;
        lastNotifTs = System.currentTimeMillis();
        notify(buildNotification(getString(R.string.n_remote_off),
                getString(R.string.n_remote_off_sub), null, ACT_NONE));
    }

    private void updateNotificationPaused() {
        if (System.currentTimeMillis() - lastNotifTs < 30_000L) return;
        lastNotifTs = System.currentTimeMillis();
        String win = fmtMin(prefs.schedFrom()) + " – " + fmtMin(prefs.schedTo());
        notify(buildNotification(getString(R.string.n_paused),
                getString(R.string.n_paused_sub, win), null, ACT_STOP));
    }

    private void updateNotificationLocked() {
        // كان يُعاد نشر الإشعار نفسه كل ثانيتين بلا توقف (حمل وحرارة): مرة كل 30 ثانية تكفي
        if (System.currentTimeMillis() - lastNotifTs < 30_000L) return;
        lastNotifTs = System.currentTimeMillis();
        notify(buildNotification(getString(R.string.n_locked_title),
                getString(R.string.notif_locked), null, ACT_NONE));
    }

    private void notify(Notification n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private static String fmt(long sec) {
        return String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60);
    }
    private static String fmtMin(int m) {
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    // ================= القفل =================
    private void triggerLock(int cat) {
        long now = System.currentTimeMillis();
        ConfigApplier.endInstant(prefs);   // انتهى الوقت اللحظي الممنوح: يعود الإعداد الافتراضي
        locked = true;
        lockedCat = cat;
        prefs.setLocked(true, cat);

        // هل تلزم استراحة إجبارية؟ (تبصيمان للمواصلة، أو تجاوز حد الجلسة)
        boolean forceBreak = unlockCount >= 2
                || sessionMs >= prefs.breakThreshold() * 60_000L;
        if (forceBreak) {
            breakUntil = now + prefs.breakMinutes() * 60_000L;
        }
        saveState(now, true);

        pauseMedia();
        KidsAccessibilityService.goHome();
        postShowOverlay(cat);
        lastNotifTs = 0L;
        if (breakUntil > now) {
            updateOverlayBreak(now);          // استراحة: لا بصمة تلقائية
        } else {
            updateNotificationLocked();
        }
    }

    /** منع فوري من ولي الأمر (إعداد لحظي = 0): استراحة بمدة الإعداد الافتراضي، ثم يعود الافتراضي. */
    private void triggerForcedBreak() {
        if (!prefs.enabled() || prefs.remoteOff()) return;
        ConfigApplier.endInstant(prefs);
        long now = System.currentTimeMillis();
        locked = true;
        lockedCat = AppClassifier.CAT_YT;
        prefs.setLocked(true, lockedCat);
        breakUntil = now + Math.max(1, prefs.breakMinutes()) * 60_000L;
        saveState(now, true);
        pauseMedia();
        KidsAccessibilityService.goHome();
        overlayVisible = false;
        uiHandler.post(this::removeOverlay);   // أعِد بناء الطبقة كاستراحة (بلا زر على جهاز الطفل)
        postShowOverlay(lockedCat);
        lastNotifTs = 0L;
        updateOverlayBreak(now);
    }

    /** انتهى زمن الشاشة المسموح ليلاً: استراحة إجبارية، وبعدها يلزم فتح ولي الأمر. */
    private void triggerNightBreak() {
        long now = System.currentTimeMillis();
        nightScreenMs = 0L;
        prefs.setScreenUsedMs(0L);
        locked = true;
        lockedCat = AppClassifier.CAT_YT;
        prefs.setLocked(true, lockedCat);
        breakUntil = now + Math.max(1, prefs.breakMinutes()) * 60_000L;
        saveState(now, true);
        pauseMedia();
        KidsAccessibilityService.goHome();
        postShowOverlay(lockedCat);
        lastNotifTs = 0L;
        updateOverlayBreak(now);
    }

    /** فتح عادي (بصمة/رقم سري): فترة جديدة، ويُحسب كتبصيم للمواصلة. */
    private void onUnlocked() {
        if (breakUntil > System.currentTimeMillis()) return; // أثناء الاستراحة: البصمة لا تكفي
        locked = false;
        overlaySuppressUntil = 0L;
        unlockCount++;
        usedMs[AppClassifier.CAT_YT] = 0;
        usedMs[AppClassifier.CAT_GAME] = 0;
        periodMs = 0;                     // فترة جديدة، لكن زمن الجلسة يتراكم
        prefs.setLocked(false, 0);
        graceUntil = System.currentTimeMillis() + 3000L;
        saveState(System.currentTimeMillis(), true);
        releaseAudioFocus();
        postRemoveOverlay();
        lastNotifTs = 0L;
    }

    /**
     * انتهى عدّاد الاستراحة: لا نفتح الجوال تلقائياً، بل يتحوّل إلى قفل عادي
     * يتطلب بصمة ولي الأمر للمتابعة (مع بدء جلسة جديدة بعد الفتح).
     */
    private void endBreakToFingerprint() {
        QuranPass.end();
        breakUntil = 0L;
        overlaySuppressUntil = 0L;
        unlockCount = 0;
        sessionMs = 0L;
        periodMs = 0L;
        usedMs[AppClassifier.CAT_YT] = 0;
        usedMs[AppClassifier.CAT_GAME] = 0;
        locked = true;                 // يبقى مقفولاً لكن كقفل عادي
        prefs.setBreakUntil(0L);
        prefs.setLocked(true, lockedCat);
        saveState(System.currentTimeMillis(), true);
        // أعِد بناء الطبقة كقفل عادي (بزر فتح بالبصمة) واطلب البصمة
        uiHandler.post(() -> { removeOverlay(); });
        postShowOverlay(lockedCat);
        lastNotifTs = 0L;
        updateNotificationLocked();
    }

    /** يطبّق الإعداد الافتراضي المحفوظ كل مدة محددة (إن كانت الميزة مفعّلة). */
    private void maybeRevertDefaults(long now) {
        if (!prefs.revertOn() || !prefs.hasDefaults()) return;
        long rAt = prefs.revertAt();
        if (rAt == 0L) { prefs.setRevertAt(now + prefs.revertMinutes() * 60_000L); return; }
        if (now >= rAt) {
            prefs.applyDefaults();
            prefs.setRevertAt(now + prefs.revertMinutes() * 60_000L);
        }
    }

    /** إنهاء الاستراحة بالرقم السري (طوارئ): جلسة جديدة وفتح فوري. */
    private void endBreak() {
        locked = false;
        breakUntil = 0L;
        overlaySuppressUntil = 0L;
        unlockCount = 0;
        sessionMs = 0L;
        periodMs = 0L;
        usedMs[AppClassifier.CAT_YT] = 0;
        usedMs[AppClassifier.CAT_GAME] = 0;
        currentCat = AppClassifier.CAT_NONE;
        prefs.setLocked(false, 0);
        graceUntil = System.currentTimeMillis() + 3000L;
        saveState(System.currentTimeMillis(), true);
        releaseAudioFocus();
        postRemoveOverlay();
        lastNotifTs = 0L;
    }

    /** يحدّث نص طبقة القفل بعدّاد الاستراحة التنازلي وإشعارها. */
    private void updateOverlayBreak(long now) {
        long rem = Math.max(0, (breakUntil - now) / 1000L);
        final String txt = getString(R.string.break_msg, fmt(rem));
        uiHandler.post(() -> setOverlayBreak(txt));
        if (now - lastNotifTs >= 5_000L) {
            lastNotifTs = now;
            // لا زر في الإشعار: مدخل الطوارئ صار إيماءة سرّية (5 ضغطات على شاشة القفل)
            notify(buildNotification(getString(R.string.n_break),
                    getString(R.string.break_msg, fmt(rem)), null, ACT_NONE));
        }
    }

    /** يُنهي تطبيقاً في الخلفية (يتوقف الفيديو نهائياً حتى في معاينة التطبيقات الأخيرة). */
    static void killBg(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return;
        try {
            android.app.ActivityManager a = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (a != null) a.killBackgroundProcesses(pkg);
        } catch (Throwable ignored) { }
    }

    private void pauseMedia() {
        if (am == null) return;
        try {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        } catch (Throwable ignored) { }
        try {
            if (focusReq == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .setOnAudioFocusChangeListener(fc -> { })
                        .build();
            }
            if (focusReq != null) am.requestAudioFocus(focusReq);
        } catch (Throwable ignored) { }
    }

    private void releaseAudioFocus() {
        try { if (am != null && focusReq != null) am.abandonAudioFocusRequest(focusReq); }
        catch (Throwable ignored) { }
        focusReq = null;
    }


    // ================= الطبقة (الخيط الرئيسي فقط) =================
    private void postShowOverlay(int cat) {
        overlayVisible = true;
        uiHandler.post(() -> showOverlay(cat));
    }
    private void postRemoveOverlay() {
        overlayVisible = false;
        uiHandler.post(this::removeOverlay);
    }

    private void showOverlay(int cat) {
        if (overlay != null) return;
        if (!Settings.canDrawOverlays(this)) return;
        boolean onBreak = breakUntil > System.currentTimeMillis();
        overlay = LayoutInflater.from(this).inflate(R.layout.overlay_lock, null);
        overlayMsg = overlay.findViewById(R.id.lock_msg);
        overlayMsg.setText(onBreak ? getString(R.string.break_title)
                : cat == AppClassifier.CAT_YT ? getString(R.string.lock_youtube)
                : getString(R.string.lock_games));
        android.widget.Button bu = overlay.findViewById(R.id.btn_unlock);
        View sub = overlay.findViewById(R.id.lock_sub);
        // على جهاز الطفل لا مخرج محلي أثناء الاستراحة (الطوارئ من جوال ولي الأمر).
        // على جهازك (ولي الأمر/غير مُدار) يبقى زر الفتح متاحاً دائماً بالرقم السري.
        boolean childDevice = prefs.isChild();
        // اقتراح مراجعة القرآن أثناء الاستراحة (على كل الأجهزة)
        View qBox = overlay.findViewById(R.id.quran_box);
        if (qBox != null) {
            boolean canQuran = onBreak && KidsAccessibilityService.isRunning() && QuranPass.launchIntent(this) != null;
            qBox.setVisibility(canQuran ? View.VISIBLE : View.GONE);
            View qb = overlay.findViewById(R.id.btn_quran);
            if (qb != null) qb.setOnClickListener(v -> startQuranPass());
        }
        if (onBreak && childDevice) {
            // أثناء الاستراحة: عدّاد فقط بلا زر. الفتح من جوال ولي الأمر فقط.
            bu.setVisibility(View.GONE);
            if (sub != null) ((TextView) sub).setText(R.string.break_sub);
        } else {
            bu.setVisibility(View.VISIBLE);
            bu.setText(getString(R.string.unlock_btn));
            // الفتح يتم داخل طبقة القفل نفسها (لوحة أرقام) — الطبقة لا تُزال أبداً قبل
            // إدخال الرقم السري الصحيح، فلا بصمة ولا نوافذ نظام يمكن إلغاؤها.
            bu.setOnClickListener(v -> openPinPanel());
            if (sub != null) ((TextView) sub).setText(R.string.lock_sub);
        }

        // بلا FLAG_KEEP_SCREEN_ON: تُترك الشاشة تُطفأ تلقائياً، ويبقى القفل والعدّاد يعملان
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.gravity = Gravity.CENTER;
        setupPinPanel();
        try { wm.addView(overlay, lp); } catch (Throwable t) { overlay = null; }
    }

    /** يفتح صحبة القرآن أثناء الاستراحة ويرفع الطبقة ما دامت هي الظاهرة. */
    private void startQuranPass() {
        long now = System.currentTimeMillis();
        if (breakUntil <= now || !KidsAccessibilityService.isRunning()) return;
        final Intent i = QuranPass.launchIntent(this);
        if (i == null) return;
        QuranPass.start(breakUntil);
        // افتح صحبة القرآن أولاً تحت الطبقة، ثم ارفع الطبقة بعد أن تصبح نافذته في المقدمة.
        // (رفع الطبقة أولاً كان يعيد التركيز للشاشة الرئيسية لحظةً فيُنهي الإذن، فيلزم ضغطتان)
        try { startActivity(i); }
        catch (Throwable t) { QuranPass.end(); return; }
        uiHandler.postDelayed(() -> {
            if (QuranPass.active()) postRemoveOverlay();
        }, 450L);
    }

    // ================= لوحة الرقم السري داخل طبقة القفل (الخيط الرئيسي) =================
    private final StringBuilder pinBuf = new StringBuilder();

    private void setupPinPanel() {
        if (overlay == null) return;
        pinBuf.setLength(0);
        int[] ids = { R.id.k_0, R.id.k_1, R.id.k_2, R.id.k_3, R.id.k_4,
                      R.id.k_5, R.id.k_6, R.id.k_7, R.id.k_8, R.id.k_9 };
        for (int d = 0; d < ids.length; d++) {
            final char ch = (char) ('0' + d);
            View k = overlay.findViewById(ids[d]);
            if (k != null) k.setOnClickListener(v -> {
                if (pinBuf.length() < 12) pinBuf.append(ch);
                setPinError(null);
                renderPinDots();
            });
        }
        View del = overlay.findViewById(R.id.k_del);
        if (del != null) {
            del.setOnClickListener(v -> {
                if (pinBuf.length() > 0) pinBuf.setLength(pinBuf.length() - 1);
                renderPinDots();
            });
            del.setOnLongClickListener(v -> { pinBuf.setLength(0); renderPinDots(); return true; });
        }
        View ok = overlay.findViewById(R.id.k_ok);
        if (ok != null) ok.setOnClickListener(v -> submitPin());
    }

    /** يُظهر لوحة الأرقام داخل الطبقة (بدون إزالة الطبقة). */
    private void openPinPanel() {
        if (overlay == null) return;
        boolean onBreak = breakUntil > System.currentTimeMillis();
        if (onBreak && prefs.isChild()) return;   // جهاز الطفل أثناء الاستراحة: من جوال ولي الأمر فقط
        View panel = overlay.findViewById(R.id.pin_panel);
        if (panel == null) return;
        panel.setVisibility(View.VISIBLE);
        View bu = overlay.findViewById(R.id.btn_unlock);
        if (bu != null) bu.setVisibility(View.GONE);
        View icon = overlay.findViewById(R.id.lock_icon);
        if (icon != null) icon.setVisibility(View.GONE);
        View qBox = overlay.findViewById(R.id.quran_box);
        if (qBox != null) qBox.setVisibility(View.GONE);   // مساحة للوحة الأرقام
        pinBuf.setLength(0);
        renderPinDots();
        if (prefs.pinLocked()) setPinError(getString(R.string.pin_locked_fmt, (int) ((prefs.pinLockedSec() + 59) / 60)));
        else if (prefs.isChild() && !prefs.hasPin()) setPinError(getString(R.string.pin_none_set));
        else setPinError(getString(R.string.pin_pad_hint));
    }

    private void renderPinDots() {
        if (overlay == null) return;
        TextView dots = overlay.findViewById(R.id.pin_dots);
        if (dots == null) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pinBuf.length(); i++) b.append('●');
        dots.setText(b.toString());
    }

    private void setPinError(String msg) {
        if (overlay == null) return;
        TextView e = overlay.findViewById(R.id.pin_err);
        if (e != null) e.setText(msg == null ? "" : msg);
    }

    private void submitPin() {
        final String pin = pinBuf.toString();
        pinBuf.setLength(0);
        renderPinDots();
        final boolean child = prefs.isChild();
        final boolean onBreak = breakUntil > System.currentTimeMillis();
        if (onBreak && child) return;
        if (prefs.pinLocked()) {
            setPinError(getString(R.string.pin_locked_fmt, (int) ((prefs.pinLockedSec() + 59) / 60)));
            return;
        }
        boolean okPin;
        if (!prefs.hasPin()) okPin = !child;           // جهازك بلا رقم سري: لا حماية (كما كان)
        else okPin = !pin.isEmpty() && prefs.checkPin(pin);
        if (!okPin) {
            setPinError(prefs.pinLocked()
                    ? getString(R.string.pin_locked_fmt, (int) ((prefs.pinLockedSec() + 59) / 60))
                    : getString(R.string.pin_wrong));
            return;
        }
        // صحيح: جهازك → ينهي القفل أو الاستراحة بجلسة جديدة؛ جهاز الطفل → فتح عادي
        if (bgHandler != null) bgHandler.post(() -> { if (child) onUnlocked(); else endBreak(); });
    }

    private void removeOverlay() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
            overlay = null;
            overlayMsg = null;
        }
    }

    /** يحدّث نص الطبقة بعدّاد الاستراحة (الخيط الرئيسي). */
    private void setOverlayBreak(String txt) {
        if (overlayMsg != null) {
            try { overlayMsg.setText(txt); } catch (Throwable ignored) { }
        }
    }

    // ================= بناء الإشعار =================
    private static final int ACT_NONE = 0, ACT_STOP = 1, ACT_START = 2, ACT_BREAK = 3;

    private Notification buildNotification(String title, String text, String big, int actionMode) {
        // على جهاز الطفل: لا يظهر إجراء «إيقاف» في الإشعار (التحكّم من جوال ولي الأمر)
        if (actionMode == ACT_STOP && prefs.isChild()) actionMode = ACT_NONE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = new Notification.Builder(this, CH_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (big != null) nb.setStyle(new Notification.BigTextStyle().bigText(big));

        if (actionMode == ACT_STOP) {
            // الإيقاف يفتح التطبيق ليطلب الرقم السري
            Intent stop = new Intent(this, MainActivity.class)
                    .putExtra("stop_request", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent spi = PendingIntent.getActivity(this, 2, stop,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nb.addAction(new Notification.Action.Builder(
                    null, getString(R.string.n_act_stop), spi).build());
        } else if (actionMode == ACT_START) {
            // التشغيل بضغطة مباشرة
            Intent start = new Intent(this, MonitorService.class).setAction(ACTION_START);
            PendingIntent spi = PendingIntent.getForegroundService(this, 1, start,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nb.addAction(new Notification.Action.Builder(
                    null, getString(R.string.n_act_start), spi).build());
        } else if (actionMode == ACT_BREAK) {
            // مدخل الطوارئ: يُزيل الطبقة ثم يفتح شاشة إدخال الرقم السري فوق كل شيء
            Intent em = new Intent(this, MonitorService.class).setAction(ACTION_EMERGENCY);
            PendingIntent epi = PendingIntent.getForegroundService(this, 3, em,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nb.addAction(new Notification.Action.Builder(
                    null, getString(R.string.n_act_emergency), epi).build());
        }
        return nb.build();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { prefs.setAliveTs(0L); } catch (Throwable ignored) { }   // إيقاف مقصود: لا يُحتسب كتوقف مفاجئ
        if (bgHandler != null) bgHandler.removeCallbacksAndMessages(null);
        if (bgThread != null) bgThread.quitSafely();
        removeOverlay();
        releaseAudioFocus();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(unlockReceiver);
        try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
