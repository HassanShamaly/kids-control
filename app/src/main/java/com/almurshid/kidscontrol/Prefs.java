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

    // أيام مفتوحة (حد أوسع): مثل الجمعة
    public static final String K_RELAX_ON    = "relax_on";
    public static final String K_RELAX_DAYS  = "relax_days";   // أرقام أيام Calendar (الأحد=1 .. السبت=7)
    public static final String K_RELAX_LIMIT = "relax_limit_min";

    // استثناء سريع لفيسبوك/إنستاجرام
    public static final String K_EXCLUDE_FB = "exclude_fb";
    public static final String K_EXCLUDE_IG = "exclude_ig";

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

    /** نسخة على ملف آخر (تُستخدم كمسودّة تحرير لا تمسّ إعدادات هذا الجهاز الفعلية). */
    private Prefs(Context c, String file) {
        sp = c.getApplicationContext().getSharedPreferences(file, Context.MODE_PRIVATE);
    }

    /** مسودّة تحرير إعدادات الجهاز المستهدف في شاشة الإعدادات. */
    public static Prefs draft(Context c) { return new Prefs(c, "kc_edit_draft"); }

    /** يمسح كل القيم (للمسودّة فقط). */
    public void clearAll() { sp.edit().clear().apply(); }

    // ===== الإعداد الافتراضي/اللحظي (القادم من جهاز ولي الأمر أو المطبَّق محلياً) =====
    private static final String K_BASE_CFG      = "base_cfg_json";     // الإعداد الافتراضي الأساسي
    private static final String K_INSTANT_UNTIL = "instant_until";     // نهاية الإعداد اللحظي
    private static final String K_LAST_DEF_REV  = "last_default_rev";  // آخر إصدار افتراضي طُبّق
    private static final String K_LAST_INSTANT  = "last_instant_seen"; // آخر أمر لحظي نُفّذ
    public String baseConfigJson()          { return sp.getString(K_BASE_CFG, ""); }
    public void setBaseConfigJson(String v) { sp.edit().putString(K_BASE_CFG, v == null ? "" : v).apply(); }
    public long instantUntil()              { return sp.getLong(K_INSTANT_UNTIL, 0L); }
    public void setInstantUntil(long v)     { sp.edit().putLong(K_INSTANT_UNTIL, v).apply(); }
    public long lastDefaultRev()            { return sp.getLong(K_LAST_DEF_REV, 0L); }
    public void setLastDefaultRev(long v)   { sp.edit().putLong(K_LAST_DEF_REV, v).apply(); }
    public long lastInstantSeen()           { return sp.getLong(K_LAST_INSTANT, 0L); }
    public void setLastInstantSeen(long v)  { sp.edit().putLong(K_LAST_INSTANT, v).apply(); }

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

    // ===== أيام مفتوحة (حد أوسع) =====
    public boolean relaxOn()   { return sp.getBoolean(K_RELAX_ON, true); }
    public int relaxLimit()    { return sp.getInt(K_RELAX_LIMIT, 60); }   // ساعة افتراضياً
    public void setRelaxOn(boolean v) { sp.edit().putBoolean(K_RELAX_ON, v).apply(); }
    public void setRelaxLimit(int v)  { sp.edit().putInt(K_RELAX_LIMIT, Math.max(1, v)).apply(); }
    public java.util.Set<String> relaxDays() {
        java.util.Set<String> def = new java.util.HashSet<>();
        def.add("6"); // الجمعة (الأحد=1 .. الجمعة=6 .. السبت=7)
        return new java.util.HashSet<>(sp.getStringSet(K_RELAX_DAYS, def));
    }
    public void setRelaxDays(java.util.Set<String> v) {
        sp.edit().putStringSet(K_RELAX_DAYS, new java.util.HashSet<>(v)).apply();
    }
    public boolean isRelaxedToday() {
        if (!relaxOn()) return false;
        int dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK);
        return relaxDays().contains(String.valueOf(dow));
    }

    /** الحد الفعّال (بالدقائق): الليل (أشد) ثم اليوم المفتوح (أوسع) ثم العادي. */
    public int effectiveLimit(boolean video) {
        if (inNightWindow()) return Math.max(1, nightLimit());
        if (isRelaxedToday()) return Math.max(1, relaxLimit());
        return video ? ytLimit() : gameLimit();
    }

    // ===== استثناء سريع لفيسبوك/إنستاجرام =====
    public static final String[] FB_PKGS = { "com.facebook.katana", "com.facebook.lite" };
    public static final String[] IG_PKGS = { "com.instagram.android" };
    public boolean excludeFb() { return sp.getBoolean(K_EXCLUDE_FB, false); }
    public boolean excludeIg() { return sp.getBoolean(K_EXCLUDE_IG, false); }
    public void setExcludeFb(boolean v) { applyExclusion(K_EXCLUDE_FB, v, FB_PKGS); }
    public void setExcludeIg(boolean v) { applyExclusion(K_EXCLUDE_IG, v, IG_PKGS); }
    private void applyExclusion(String key, boolean on, String[] pkgs) {
        java.util.Set<String> allowed = getSet(K_ALLOWED);
        if (on) { for (String p : pkgs) allowed.add(p); }
        else    { for (String p : pkgs) allowed.remove(p); }
        putSet(K_ALLOWED, allowed);
        sp.edit().putBoolean(key, on).apply();
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

    // ===== نافذة تثبيت التحديث (خلالها لا تحرس خدمة الحماية شاشة التثبيت) =====
    private static final String K_INSTALL_UNTIL = "install_until";
    public void setInstallUntil(long ts) { sp.edit().putLong(K_INSTALL_UNTIL, ts).apply(); }
    public boolean installActive() {
        return System.currentTimeMillis() < sp.getLong(K_INSTALL_UNTIL, 0L);
    }

    // ===== وضع ولي الأمر (يسمح بفتح إعدادات التطبيق في النظام مؤقتاً) =====
    public void setParentUntil(long ts) { sp.edit().putLong(K_PARENT_UNTIL, ts).apply(); }
    public boolean parentModeActive() {
        return System.currentTimeMillis() < sp.getLong(K_PARENT_UNTIL, 0L);
    }

    // ===== التحكّم عن بُعد (جهاز ولي الأمر يتحكّم، أجهزة الأطفال تقرأ) =====
    public static final int ROLE_NONE = 0, ROLE_PARENT = 1, ROLE_CHILD = 2;
    private static final String K_ROLE   = "device_role";
    private static final String K_FAMILY = "family_code";
    private static final String K_SYNCURL = "sync_url";
    private static final String K_LAST_PULL = "last_pull";

    public int role()            { return sp.getInt(K_ROLE, ROLE_NONE); }
    public void setRole(int v)   { sp.edit().putInt(K_ROLE, v).apply(); }
    public boolean isParent()    { return role() == ROLE_PARENT; }
    public boolean isChild()     { return role() == ROLE_CHILD; }

    public String familyCode()   { return sp.getString(K_FAMILY, ""); }
    public void setFamilyCode(String v) {
        sp.edit().putString(K_FAMILY, v == null ? "" : v.trim()).apply();
    }
    /** رابط قاعدة Firebase الافتراضي (مشروع العائلة) — يمكن تغييره من الإعدادات. */
    public static final String DEFAULT_SYNC_URL = "https://sohba-52557-default-rtdb.firebaseio.com";
    public String syncUrl()      { return sp.getString(K_SYNCURL, DEFAULT_SYNC_URL); }
    public void setSyncUrl(String v) {
        String s = v == null ? "" : v.trim();
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        sp.edit().putString(K_SYNCURL, s).apply();
    }
    public boolean syncConfigured() {
        return syncUrl().length() > 6 && familyCode().length() > 0;
    }
    public long lastPull()       { return sp.getLong(K_LAST_PULL, 0L); }
    public void setLastPull(long v) { sp.edit().putLong(K_LAST_PULL, v).apply(); }

    // أمر الفتح عن بُعد: ولي الأمر يضبط طابعاً زمنياً جديداً؛ الطفل ينفّذه مرة واحدة إن كان حديثاً
    private static final String K_UNLOCK_CMD = "remote_unlock_ts";   // على جهاز ولي الأمر
    private static final String K_UNLOCK_SEEN = "remote_unlock_seen"; // على جهاز الطفل
    // تعريف الجهاز (لتخصيص إعداد مستقل لكل جوال)
    private static final String K_DEVICE_ID = "device_id";
    private static final String K_DEVICE_NAME = "device_name";
    private static final String K_TARGET_ID = "target_device_id"; // على جهاز ولي الأمر: الجهاز المحدَّد للضبط
    public String deviceId() {
        String id = sp.getString(K_DEVICE_ID, null);
        if (id == null || id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            sp.edit().putString(K_DEVICE_ID, id).apply();
        }
        return id;
    }
    public String deviceName()          { return sp.getString(K_DEVICE_NAME, ""); }
    public void setDeviceName(String v)  { sp.edit().putString(K_DEVICE_NAME, v == null ? "" : v.trim()).apply(); }
    public String targetId()            { return sp.getString(K_TARGET_ID, ""); }
    public void setTargetId(String v)    { sp.edit().putString(K_TARGET_ID, v == null ? "" : v).apply(); }

    public long remoteUnlockTs()        { return sp.getLong(K_UNLOCK_CMD, 0L); }
    public void setRemoteUnlockTs(long v){ sp.edit().putLong(K_UNLOCK_CMD, v).apply(); }
    public long lastUnlockSeen()        { return sp.getLong(K_UNLOCK_SEEN, 0L); }
    public void setLastUnlockSeen(long v){ sp.edit().putLong(K_UNLOCK_SEEN, v).apply(); }

    /** يجمّع الإعدادات القابلة للمزامنة في JSON (ينشره جهاز ولي الأمر). */
    public org.json.JSONObject exportConfig() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("yt_on", ytOn());         o.put("game_on", gameOn());
            o.put("yt", ytLimit());         o.put("game", gameLimit());
            o.put("cooldown", cooldownSec());
            o.put("sched_on", schedOn());   o.put("sched_from", schedFrom()); o.put("sched_to", schedTo());
            o.put("relax_on", relaxOn());   o.put("relax_limit", relaxLimit());
            o.put("relax_days", new org.json.JSONArray(relaxDays()));
            o.put("night_on", nightOn());   o.put("night_from", nightFrom()); o.put("night_to", nightTo()); o.put("night_limit", nightLimit());
            o.put("break_min", breakMinutes()); o.put("break_thr", breakThreshold());
            o.put("resume_min", resumeMinutes());
            o.put("revert_on", revertOn()); o.put("revert_min", revertMinutes());
            o.put("exc_fb", excludeFb());   o.put("exc_ig", excludeIg());
            o.put("allowed", new org.json.JSONArray(getSet(K_ALLOWED)));
            o.put("rev", System.currentTimeMillis());
        } catch (Exception ignored) { }
        return o;
    }

    /** يطبّق إعدادات واردة من جهاز ولي الأمر (على جهاز الطفل). */
    public void applyConfig(org.json.JSONObject o) {
        if (o == null) return;
        SharedPreferences.Editor e = sp.edit();
        if (o.has("yt_on"))       e.putBoolean(K_YT_ON, o.optBoolean("yt_on", ytOn()));
        if (o.has("game_on"))     e.putBoolean(K_GAME_ON, o.optBoolean("game_on", gameOn()));
        if (o.has("yt"))          e.putInt(K_YT_LIMIT, o.optInt("yt", ytLimit()));
        if (o.has("game"))        e.putInt(K_GAME_LIMIT, o.optInt("game", gameLimit()));
        if (o.has("cooldown"))    e.putInt(K_COOLDOWN, o.optInt("cooldown", cooldownSec()));
        if (o.has("sched_on"))    e.putBoolean(K_SCHED_ON, o.optBoolean("sched_on", schedOn()));
        if (o.has("sched_from"))  e.putInt(K_SCHED_FROM, o.optInt("sched_from", schedFrom()));
        if (o.has("sched_to"))    e.putInt(K_SCHED_TO, o.optInt("sched_to", schedTo()));
        if (o.has("relax_on"))    e.putBoolean(K_RELAX_ON, o.optBoolean("relax_on", relaxOn()));
        if (o.has("relax_limit")) e.putInt(K_RELAX_LIMIT, o.optInt("relax_limit", relaxLimit()));
        if (o.has("night_on"))    e.putBoolean(K_NIGHT_ON, o.optBoolean("night_on", nightOn()));
        if (o.has("night_from"))  e.putInt(K_NIGHT_FROM, o.optInt("night_from", nightFrom()));
        if (o.has("night_to"))    e.putInt(K_NIGHT_TO, o.optInt("night_to", nightTo()));
        if (o.has("night_limit")) e.putInt(K_NIGHT_LIMIT, o.optInt("night_limit", nightLimit()));
        if (o.has("break_min"))   e.putInt(K_BREAK_MIN, o.optInt("break_min", breakMinutes()));
        if (o.has("break_thr"))   e.putInt(K_BREAK_THRESHOLD, o.optInt("break_thr", breakThreshold()));
        if (o.has("resume_min"))  e.putInt(K_RESUME_MIN, o.optInt("resume_min", resumeMinutes()));
        if (o.has("revert_on"))   e.putBoolean(K_REVERT_ON, o.optBoolean("revert_on", revertOn()));
        if (o.has("revert_min"))  e.putInt(K_REVERT_MIN, o.optInt("revert_min", revertMinutes()));
        if (o.has("exc_fb"))      e.putBoolean(K_EXCLUDE_FB, o.optBoolean("exc_fb", excludeFb()));
        if (o.has("exc_ig"))      e.putBoolean(K_EXCLUDE_IG, o.optBoolean("exc_ig", excludeIg()));
        e.apply();
        if (o.has("relax_days")) {
            java.util.Set<String> s = new java.util.HashSet<>();
            org.json.JSONArray a = o.optJSONArray("relax_days");
            if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
            putSet(K_RELAX_DAYS, s);
        }
        if (o.has("allowed")) {
            java.util.Set<String> s = new java.util.HashSet<>();
            org.json.JSONArray a = o.optJSONArray("allowed");
            if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
            putSet(K_ALLOWED, s);
        }
    }
}
