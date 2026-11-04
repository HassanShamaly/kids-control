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
    public static final String ACTION_SUPPRESS_OVERLAY = "com.almurshid.kidscontrol.SUPPRESS_OVERLAY"; // أخفِ الطبقة مؤقتاً لإدخال الرقم
    public static final String ACTION_RESHOW_OVERLAY   = "com.almurshid.kidscontrol.RESHOW_OVERLAY";
    public static final String ACTION_EMERGENCY        = "com.almurshid.kidscontrol.EMERGENCY";  // مدخل الطوارئ من الإشعار

    private static final String CH_ID = "kids_control_status";
    private static final int NOTIF_ID = 1001;
    private static final long POLL_MS = 2000L;

    private static volatile boolean running = false;
    public static boolean isActive() { return running; }

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
    private long overlaySuppressUntil = 0L; // إخفاء الطبقة مؤقتاً أثناء إدخال الرقم

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
        sessionMs = prefs.sessionMs();
        unlockCount = prefs.unlockCount();
        breakUntil = prefs.breakUntil();
        if (prefs.locked()) { locked = true; lockedCat = prefs.lockedCat(); }

        loop = new Runnable() {
            @Override public void run() {
                try { poll(); } catch (Throwable ignored) {}
                bgHandler.postDelayed(this, POLL_MS);
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
        if (ACTION_SUPPRESS_OVERLAY.equals(action)) {
            if (bgHandler != null) bgHandler.post(() -> {
                overlaySuppressUntil = System.currentTimeMillis() + 25_000L;
                postRemoveOverlay();
            });
            return START_STICKY;
        }
        if (ACTION_RESHOW_OVERLAY.equals(action)) {
            if (bgHandler != null) bgHandler.post(() -> overlaySuppressUntil = 0L);
            return START_STICKY;
        }
        if (ACTION_EMERGENCY.equals(action)) {
            // أزِل الطبقة أولاً ثم افتح شاشة إدخال الرقم فوق كل شيء
            if (bgHandler != null) bgHandler.post(() -> {
                overlaySuppressUntil = System.currentTimeMillis() + 60_000L;
                postRemoveOverlay();
                uiHandler.postDelayed(() -> {
                    Intent u = new Intent(this, UnlockActivity.class)
                            .putExtra("emergency", true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    try { startActivity(u); } catch (Throwable ignored) { }
                }, 250L);
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
            boolean suppressed = now < overlaySuppressUntil; // إدخال رقم سري جارٍ
            if (!overlayVisible && !suppressed) { postShowOverlay(lockedCat); }
            if (onBreak) updateOverlayBreak(now); else if (!suppressed) updateNotificationLocked();
            Set<String> playing = KidsNotificationListener.playingPackages(this);
            if ((playing != null && !playing.isEmpty()) || am.isMusicActive()) pauseMedia();
            return;
        }

        String fgUsage = usageForeground(now);
        String fgA11y = KidsAccessibilityService.currentForeground();
        boolean screenOn = power == null || power.isInteractive();
        boolean deviceLocked = keyguard != null && keyguard.isKeyguardLocked();
        boolean active = screenOn && !deviceLocked;

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
        updateNotification(monitored ? cat : AppClassifier.CAT_NONE, fg, cat, now);
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
        if (now - lastNotifTs < 3_000L) return;
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

    private void updateNotificationPaused() {
        if (System.currentTimeMillis() - lastNotifTs < 30_000L) return;
        lastNotifTs = System.currentTimeMillis();
        String win = fmtMin(prefs.schedFrom()) + " – " + fmtMin(prefs.schedTo());
        notify(buildNotification(getString(R.string.n_paused),
                getString(R.string.n_paused_sub, win), null, ACT_STOP));
    }

    private void updateNotificationLocked() {
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
            uiHandler.postDelayed(this::launchUnlock, 700L);  // قفل عادي: اطلب البصمة
            updateNotificationLocked();
        }
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
        uiHandler.postDelayed(this::launchUnlock, 700L);
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
            notify(buildNotification(getString(R.string.n_break),
                    getString(R.string.break_msg, fmt(rem)), null, ACT_BREAK));
        }
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

    private void launchUnlock() {
        if (!locked) return;
        Intent i = new Intent(this, UnlockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(i); } catch (Throwable ignored) {}
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
        if (onBreak) {
            // أثناء الاستراحة: عدّاد فقط بلا زر ولا نص مساعد، ليظن الطفل ألا حلّ.
            // مدخل الطوارئ لولي الأمر من الإشعار.
            bu.setVisibility(View.GONE);
            if (sub != null) ((TextView) sub).setText(R.string.break_sub);
        } else {
            bu.setVisibility(View.VISIBLE);
            bu.setText(getString(R.string.unlock_btn));
            bu.setOnClickListener(v -> launchUnlock());
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
        try { wm.addView(overlay, lp); } catch (Throwable t) { overlay = null; }
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
        if (bgHandler != null) bgHandler.removeCallbacksAndMessages(null);
        if (bgThread != null) bgThread.quitSafely();
        removeOverlay();
        releaseAudioFocus();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(unlockReceiver);
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }
}
