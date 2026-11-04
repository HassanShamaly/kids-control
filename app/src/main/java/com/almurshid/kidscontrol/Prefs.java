package com.almurshid.kidscontrol;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** تخزين الإعدادات: المدد، الرقم السري، حالة التشغيل، القوائم اليدوية، وحالة القفل. */
public class Prefs {
    private static final String FILE = "kids_control_prefs";

    public static final String K_YT_LIMIT   = "yt_limit_min";    // مدة الفيديو بالدقائق
    public static final String K_GAME_LIMIT = "game_limit_min";  // مدة الألعاب بالدقائق
    public static final String K_YT_ON       = "yt_on";
    public static final String K_GAME_ON     = "game_on";
    public static final String K_PIN         = "pin";
    public static final String K_ENABLED     = "monitor_enabled";
    public static final String K_COOLDOWN    = "cooldown_sec";    // مدة الابتعاد لإعادة تصفير العدّاد

    // قوائم يدوية
    public static final String K_INC_VIDEO = "inc_video";   // تُحسب فيديو دائماً
    public static final String K_EXC_VIDEO = "exc_video";   // لا تُحسب فيديو رغم الاكتشاف التلقائي
    public static final String K_INC_GAME  = "inc_game";
    public static final String K_EXC_GAME  = "exc_game";
    public static final String K_ALLOWED   = "allowed_always"; // لا تُحسب أبداً

    // جدول التعطيل اليومي (خلاله لا تعمل المراقبة)
    public static final String K_SCHED_ON   = "sched_on";
    public static final String K_SCHED_FROM = "sched_from_min"; // دقائق من منتصف الليل
    public static final String K_SCHED_TO   = "sched_to_min";

    // قيود الليل: خلال الفترة يصبح الحد = دقيقة واحدة (أو ما تختاره)
    public static final String K_NIGHT_ON    = "night_on";
    public static final String K_NIGHT_FROM  = "night_from_min";
    public static final String K_NIGHT_TO    = "night_to_min";
    public static final String K_NIGHT_LIMIT = "night_limit_min";

    // الاستراحة الإجبارية
    public static final String K_BREAK_MIN       = "break_min";        // مدة الاستراحة
    public static final String K_BREAK_THRESHOLD = "break_threshold";  // دقائق متواصلة تُلزم الاستراحة

    // عودة المراقبة تلقائياً بعد الإيقاف اليدوي
    public static final String K_RESUME_MIN = "resume_min";   // مدة الإيقاف المؤقت
    private static final String K_RESUME_AT = "resume_at";    // وقت العودة التلقائية (0 = لا شيء)

    // الإعداد الافتراضي والعودة إليه دورياً
    public static final String K_REVERT_ON  = "revert_on";
    public static final String K_REVERT_MIN = "revert_min";
    private static final String K_REVERT_AT = "revert_at";
    private static final String K_DEF_SET   = "def_set";      // هل حُفظ إعداد افتراضي؟
    private static final String DEF_PREFIX  = "def_";

    /** المفاتيح التي يشملها الإعداد الافتراضي. */
    private static final String[] DEFAULT_KEYS = {
        K_YT_LIMIT, K_GAME_LIMIT, K_COOLDOWN, K_YT_ON, K_GAME_ON,
        K_NIGHT_ON, K_NIGHT_FROM, K_NIGHT_TO, K_NIGHT_LIMIT,
        K_BREAK_THRESHOLD, K_BREAK_MIN, K_RESUME_MIN,
        K_SCHED_ON, K_SCHED_FROM, K_SCHED_TO
    };
    private static final java.util.Set<String> BOOL_KEYS = new java.util.HashSet<>(
        java.util.Arrays.asList(K_YT_ON, K_GAME_ON, K_NIGHT_ON, K_SCHED_ON));
    // حالة الجلسة والاستراحة (محفوظة)
    private static final String K_UNLOCK_COUNT = "unlock_count";
    private static final String K_SESSION_MS   = "session_ms";
    private static final String K_BREAK_UNTIL  = "break_until";

    // حالة محفوظة (تصمد أمام إعادة التشغيل)
    private static final String K_LOCKED      = "locked";
    private static final String K_LOCKED_CAT  = "locked_cat";
    private static final String K_USED_YT     = "used_yt";
    private static final String K_USED_GAME   = "used_game";
    private static final String K_PERIOD_MS   = "period_ms";
    private static final String K_LAST_MON    = "last_monitored";
    private static final String K_CUR_CAT     = "current_cat";
    private static final String K_PARENT_UNTIL = "parent_until";

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int ytLimit()     { return sp.getInt(K_YT_LIMIT, 30); }
    public int gameLimit()   { return sp.getInt(K_GAME_LIMIT, 30); }
    public boolean ytOn()    { return sp.getBoolean(K_YT_ON, true); }
    public boolean gameOn()  { return sp.getBoolean(K_GAME_ON, true); }
    public boolean enabled() { return sp.getBoolean(K_ENABLED, false); }
    public int cooldownSec() { return sp.getInt(K_COOLDOWN, 600); } // 10 دقائق افتراضياً

    public String pin()      { return sp.getString(K_PIN, null); }
    public boolean hasPin()  { return pin() != null && pin().length() >= 4; }
    public boolean checkPin(String p) { return hasPin() && pin().equals(p); }

    public void setYtLimit(int v)   { sp.edit().putInt(K_YT_LIMIT, v).apply(); }
    public void setGameLimit(int v) { sp.edit().putInt(K_GAME_LIMIT, v).apply(); }
    public void setYtOn(boolean v)  { sp.edit().putBoolean(K_YT_ON, v).apply(); }
    public void setGameOn(boolean v){ sp.edit().putBoolean(K_GAME_ON, v).apply(); }
    public void setEnabled(boolean v){ sp.edit().putBoolean(K_ENABLED, v).apply(); }
    public void setCooldownSec(int v){ sp.edit().putInt(K_COOLDOWN, v).apply(); }
    public void setPin(String p)    { sp.edit().putString(K_PIN, p).apply(); }

    // ===== القوائم =====
    public Set<String> getSet(String key) {
        return new HashSet<>(sp.getStringSet(key, new HashSet<>()));
    }
    public void putSet(String key, Set<String> v) {
        sp.edit().putStringSet(key, new HashSet<>(v)).apply();
    }

    // ===== القفل =====
    public boolean locked()  { return sp.getBoolean(K_LOCKED, false); }
    public int lockedCat()   { return sp.getInt(K_LOCKED_CAT, 0); }
    public void setLocked(boolean v, int cat) {
        sp.edit().putBoolean(K_LOCKED, v).putInt(K_LOCKED_CAT, cat).apply();
    }

    // ===== العدّادات =====
    public long usedYt()        { return sp.getLong(K_USED_YT, 0L); }
    public long usedGame()      { return sp.getLong(K_USED_GAME, 0L); }
    public long lastMonitored() { return sp.getLong(K_LAST_MON, 0L); }
    public int currentCat()     { return sp.getInt(K_CUR_CAT, 0); }
    public long periodMs()      { return sp.getLong(K_PERIOD_MS, 0L); }
    public void saveUsage(long yt, long game, long lastMon, int curCat) {
        sp.edit().putLong(K_USED_YT, yt).putLong(K_USED_GAME, game)
                .putLong(K_LAST_MON, lastMon).putInt(K_CUR_CAT, curCat).apply();
    }
    public void setPeriodMs(long v) { sp.edit().putLong(K_PERIOD_MS, v).apply(); }

    // ===== جدول التعطيل اليومي =====
    public boolean schedOn()  { return sp.getBoolean(K_SCHED_ON, false); }
    public int schedFrom()    { return sp.getInt(K_SCHED_FROM, 8 * 60); }   // 8:00 ص
    public int schedTo()      { return sp.getInt(K_SCHED_TO, 15 * 60); }    // 3:00 م
    public void setSchedOn(boolean v) { sp.edit().putBoolean(K_SCHED_ON, v).apply(); }
    public void setSchedFrom(int v)   { sp.edit().putInt(K_SCHED_FROM, v).apply(); }
    public void setSchedTo(int v)     { sp.edit().putInt(K_SCHED_TO, v).apply(); }

    /** هل نحن الآن داخل فترة التعطيل (فتُوقَف المراقبة)؟ */
    public boolean inPauseWindow() {
        if (!schedOn()) return false;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int from = schedFrom(), to = schedTo();
        if (from == to) return false;
        if (from < to) return m >= from && m < to;      // نفس اليوم
        return m >= from || m < to;                       // يمتد عبر منتصف الليل
    }

    // ===== قيود الليل =====
    public boolean nightOn() { return sp.getBoolean(K_NIGHT_ON, true); }
    public int nightFrom()   { return sp.getInt(K_NIGHT_FROM, 0); }       // 12:00 ص
    public int nightTo()     { return sp.getInt(K_NIGHT_TO, 6 * 60); }    // 6:00 ص
    public int nightLimit()  { return sp.getInt(K_NIGHT_LIMIT, 1); }      // دقيقة واحدة
    public void setNightOn(boolean v) { sp.edit().putBoolean(K_NIGHT_ON, v).apply(); }
    public void setNightFrom(int v)   { sp.edit().putInt(K_NIGHT_FROM, v).apply(); }
    public void setNightTo(int v)     { sp.edit().putInt(K_NIGHT_TO, v).apply(); }
    public void setNightLimit(int v)  { sp.edit().putInt(K_NIGHT_LIMIT, v).apply(); }

    public boolean inNightWindow() {
        if (!nightOn()) return false;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        int from = nightFrom(), to = nightTo();
        if (from == to) return false;
        if (from < to) return m >= from && m < to;
        return m >= from || m < to;
    }

    /** الحد الفعّال (بالدقائق) لفئة معيّنة، مع مراعاة قيود الليل. */
    public int effectiveLimit(boolean video) {
        int base = video ? ytLimit() : gameLimit();
        if (inNightWindow()) return Math.max(1, nightLimit());
        return base;
    }

    // ===== الاستراحة الإجبارية =====
    public int breakMinutes()      { return sp.getInt(K_BREAK_MIN, 60); }
    public int breakThreshold()    { return sp.getInt(K_BREAK_THRESHOLD, 20); }
    public void setBreakMinutes(int v)   { sp.edit().putInt(K_BREAK_MIN, Math.max(1, v)).apply(); }
    public void setBreakThreshold(int v) { sp.edit().putInt(K_BREAK_THRESHOLD, Math.max(1, v)).apply(); }

    public int unlockCount()       { return sp.getInt(K_UNLOCK_COUNT, 0); }
    public long sessionMs()        { return sp.getLong(K_SESSION_MS, 0L); }
    public long breakUntil()       { return sp.getLong(K_BREAK_UNTIL, 0L); }
    public boolean breakActive()   { return System.currentTimeMillis() < breakUntil(); }
    public void setUnlockCount(int v) { sp.edit().putInt(K_UNLOCK_COUNT, v).apply(); }
    public void setSessionMs(long v)  { sp.edit().putLong(K_SESSION_MS, v).apply(); }
    public void setBreakUntil(long v) { sp.edit().putLong(K_BREAK_UNTIL, v).apply(); }

    // ===== الإيقاف المؤقت والعودة التلقائية =====
    public int resumeMinutes()      { return sp.getInt(K_RESUME_MIN, 30); }
    public void setResumeMinutes(int v) { sp.edit().putInt(K_RESUME_MIN, Math.max(1, v)).apply(); }
    public long resumeAt()          { return sp.getLong(K_RESUME_AT, 0L); }
    public void setResumeAt(long v) { sp.edit().putLong(K_RESUME_AT, v).apply(); }

    // ===== الإعداد الافتراضي والعودة إليه دورياً =====
    public boolean revertOn()       { return sp.getBoolean(K_REVERT_ON, false); }
    public int revertMinutes()      { return sp.getInt(K_REVERT_MIN, 30); }
    public void setRevertOn(boolean v) { sp.edit().putBoolean(K_REVERT_ON, v).apply(); }
    public void setRevertMinutes(int v){ sp.edit().putInt(K_REVERT_MIN, Math.max(1, v)).apply(); }
    public long revertAt()          { return sp.getLong(K_REVERT_AT, 0L); }
    public void setRevertAt(long v) { sp.edit().putLong(K_REVERT_AT, v).apply(); }
    public boolean hasDefaults()    { return sp.getBoolean(K_DEF_SET, false); }

    /** يحفظ القيم الحالية كإعداد افتراضي، ويعيد ضبط مؤقّت العودة. */
    public void saveDefaultsFromCurrent() {
        SharedPreferences.Editor e = sp.edit();
        for (String k : DEFAULT_KEYS) {
            if (BOOL_KEYS.contains(k)) e.putBoolean(DEF_PREFIX + k, sp.getBoolean(k, false));
            else e.putInt(DEF_PREFIX + k, sp.getInt(k, 0));
        }
        e.putBoolean(K_DEF_SET, true);
        e.putLong(K_REVERT_AT, System.currentTimeMillis() + revertMinutes() * 60_000L);
        e.apply();
    }

    /** يطبّق الإعداد الافتراظي المحفوظ على القيم الحالية. */
    public void applyDefaults() {
        if (!hasDefaults()) return;
        SharedPreferences.Editor e = sp.edit();
        for (String k : DEFAULT_KEYS) {
            if (!sp.contains(DEF_PREFIX + k)) continue;
            if (BOOL_KEYS.contains(k)) e.putBoolean(k, sp.getBoolean(DEF_PREFIX + k, false));
            else e.putInt(k, sp.getInt(DEF_PREFIX + k, 0));
        }
        e.apply();
    }

    // ===== وضع ولي الأمر (يسمح بفتح إعدادات التطبيق في النظام مؤقتاً) =====
    public void setParentUntil(long ts) { sp.edit().putLong(K_PARENT_UNTIL, ts).apply(); }
    public boolean parentModeActive() {
        return System.currentTimeMillis() < sp.getLong(K_PARENT_UNTIL, 0L);
    }
}
