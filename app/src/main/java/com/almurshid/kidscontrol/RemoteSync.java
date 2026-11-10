package com.almurshid.kidscontrol;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * مزامنة الإعدادات عبر Firebase Realtime Database (REST مباشر، بلا مكتبة Firebase).
 * لكل جهاز ملفّه المستقل:
 *   /kc/{code}/devices/{deviceId}/config   ← يكتبها جهاز ولي الأمر (الحدود + أمر الفتح)
 *   /kc/{code}/devices/{deviceId}/meta     ← يكتبها جهاز الطفل (اسمه وآخر ظهور)
 * جهاز ولي الأمر يقرأ قائمة الأجهزة من /kc/{code}/devices.
 * كل العمليات دفاعية: أي خطأ شبكة يُتجاهل بهدوء.
 */
public class RemoteSync {

    private static volatile long lastPullTs = 0L;

    public static class Device {
        public final String id, name;
        public final long lastSeen;
        public final boolean off;      // التطبيق موقوف عن بُعد على هذا الجهاز
        Device(String id, String name, long lastSeen, boolean off) {
            this.id = id; this.name = name; this.lastSeen = lastSeen; this.off = off;
        }
    }
    public interface DevicesCallback { void onResult(List<Device> devices, boolean ok); }
    public interface ConfigCallback { void onResult(org.json.JSONObject config, boolean ok); }

    // ===== مسارات =====
    private static String base(Prefs p) {
        String b = p.syncUrl();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
    private static String devicesRoot(Prefs p) { return base(p) + "/kc/" + enc(p.familyCode()) + "/devices"; }
    private static String configNode(Prefs p, String id) { return devicesRoot(p) + "/" + enc(id) + "/config.json"; }
    private static String metaNode(Prefs p, String id) { return devicesRoot(p) + "/" + enc(id) + "/meta.json"; }
    private static String powerNode(Prefs p, String id) { return devicesRoot(p) + "/" + enc(id) + "/power.json"; }
    private static String deviceNode(Prefs p, String id) { return devicesRoot(p) + "/" + enc(id) + ".json"; }

    // ===== جهاز الطفل =====

    /** يسجّل هذا الجهاز (اسمه وآخر ظهور) ليظهر في قائمة جهاز ولي الأمر. */
    public static void registerDevice(final Context c) {
        final Prefs p = new Prefs(c);
        if (!p.isChild() || !p.syncConfigured()) return;
        final String url = metaNode(p, p.deviceId());
        String nm = p.deviceName();
        if (nm == null || nm.isEmpty()) nm = Build.MODEL;
        final String name = nm;
        new Thread(() -> {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", name);
                o.put("model", Build.MODEL);
                o.put("lastSeen", System.currentTimeMillis());
                o.put("off", p.remoteOff());
                write(url, "PUT", o.toString());
            } catch (Throwable ignored) { }
        }).start();
    }

    /** يسحب هذا الجهاز إعداده إن مرّ وقت كافٍ (من حلقة المراقبة)، ويجدّد تسجيله. */
    public static void pullIfDue(Context c, long minIntervalMs) {
        Prefs p = new Prefs(c);
        if (!p.isChild() || !p.syncConfigured()) return;
        long now = System.currentTimeMillis();
        if (now - lastPullTs < minIntervalMs) return;
        lastPullTs = now;
        registerDevice(c);
        pull(c, false);
    }

    /** جهاز الطفل: يقرأ إعداده الخاص ويطبّقه (وينفّذ أمر الفتح إن وُجد). */
    public static void pull(final Context c, final boolean showResult) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (!p.syncConfigured()) {
            if (showResult) Toast.makeText(c, R.string.sync_need_setup, Toast.LENGTH_LONG).show();
            return;
        }
        final String url = deviceNode(p, p.deviceId());   // طلب واحد: config + power + meta
        new Thread(() -> {
            String[] r = read(url);               // r[0]=body أو null ، r[1]="ok"/"err"
            final boolean httpOk = "ok".equals(r[1]);
            final String body = r[0];
            org.json.JSONObject root = null;
            if (body != null && body.trim().length() > 2 && !"null".equals(body.trim())) {
                try { root = new org.json.JSONObject(body); } catch (Throwable ignored) { }
            }
            // أمر الإيقاف/التشغيل عن بُعد
            if (root != null && p.isChild()) {
                org.json.JSONObject pw = root.optJSONObject("power");
                if (pw != null) {
                    long ts = pw.optLong("ts", 0L);
                    if (ts > 0 && ts != p.lastPowerSeen()) {
                        p.setLastPowerSeen(ts);
                        p.setRemoteOff(!pw.optBoolean("on", true));
                        registerDevice(c);   // حدّث الحالة الظاهرة عند ولي الأمر فوراً
                    }
                }
            }
            org.json.JSONObject cfgObj = root != null ? root.optJSONObject("config") : null;
            boolean hasData = cfgObj != null;
            boolean applied = false;
            if (hasData) {
                try {
                    org.json.JSONObject o = cfgObj;
                    // ١) الافتراضي: يُطبَّق فقط عند صدور نسخة جديدة منه (حتى لا يمحو اللحظي كل دقيقة)
                    long rev = o.optLong("rev", 0L);
                    if (rev != 0L && rev != p.lastDefaultRev()) {
                        org.json.JSONObject def = new org.json.JSONObject(o.toString());
                        def.remove("instant");
                        def.remove("unlock");
                        ConfigApplier.applyDefault(p, def);
                        p.setLastDefaultRev(rev);
                    }
                    // ٢) اللحظي: يُنفَّذ مرة واحدة لكل أمر جديد، ضمن مدته
                    org.json.JSONObject ins = o.optJSONObject("instant");
                    if (ins != null && p.isChild()) {
                        long ts = ins.optLong("ts", 0L);
                        if (ts > 0 && ts != p.lastInstantSeen()) {
                            p.setLastInstantSeen(ts);
                            long until = ts + Math.max(1, ins.optInt("dur", 60)) * 60_000L;
                            ConfigApplier.applyInstant(c, p, ins, until, true);
                        }
                    }
                    p.setLastPull(System.currentTimeMillis());
                    applied = true;
                    maybeRemoteUnlock(c, p, o);
                } catch (Throwable ignored) { }
            }
            final boolean fapplied = applied;
            final boolean fempty = httpOk && !hasData;
            if (showResult) ui.post(() -> Toast.makeText(c,
                    fapplied ? R.string.sync_pulled
                             : fempty ? R.string.sync_empty
                                      : R.string.sync_failed,
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private static void maybeRemoteUnlock(Context c, Prefs p, org.json.JSONObject o) {
        try {
            if (!p.isChild()) return;
            long unlock = o.optLong("unlock", 0L);
            if (unlock <= 0) return;
            long now = System.currentTimeMillis();
            if (unlock <= p.lastUnlockSeen()) return;
            p.setLastUnlockSeen(unlock);
            if (now - unlock > 15 * 60_000L) return;
            c.startService(new Intent(c, MonitorService.class).setAction(MonitorService.ACTION_REMOTE_UNLOCK));
        } catch (Throwable ignored) { }
    }

    // ===== جهاز ولي الأمر =====

    /** ينشر الإعدادات الحالية إلى الجهاز المحدَّد. */
    public static void publishToTarget(final Context c, final String targetId, final boolean showResult) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (targetId == null || targetId.isEmpty()) {
            if (showResult) Toast.makeText(c, R.string.dev_pick_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!p.syncConfigured()) {
            if (showResult) Toast.makeText(c, R.string.sync_need_setup, Toast.LENGTH_LONG).show();
            return;
        }
        final String url = configNode(p, targetId);
        final String body = p.exportConfig().toString();
        new Thread(() -> {
            boolean ok = write(url, "PUT", body);
            if (showResult) ui.post(() -> Toast.makeText(c,
                    ok ? R.string.sync_published : R.string.sync_failed, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** ينشر كائن إعداد جاهز إلى الجهاز المحدَّد (لا يقرأ إعدادات جهاز ولي الأمر إطلاقاً). */
    public static void publishConfigToTarget(final Context c, final String targetId,
                                             final org.json.JSONObject config, final boolean showResult) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (targetId == null || targetId.isEmpty()) {
            if (showResult) Toast.makeText(c, R.string.dev_pick_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!p.syncConfigured()) {
            if (showResult) Toast.makeText(c, R.string.sync_need_setup, Toast.LENGTH_LONG).show();
            return;
        }
        try { config.put("rev", System.currentTimeMillis()); } catch (Throwable ignored) { }
        final String url = configNode(p, targetId);
        final String body = config.toString();
        new Thread(() -> {
            boolean ok = write(url, "PUT", body);
            if (showResult) ui.post(() -> Toast.makeText(c,
                    ok ? R.string.default_sent : R.string.sync_failed, Toast.LENGTH_LONG).show());
        }).start();
    }

    /**
     * يرسل إعداداً «لحظياً» للجهاز المحدَّد: يُضاف كحقل instant دون المساس بالإعداد الافتراضي.
     * يطبّقه الطفل الآن بجلسة وقت جديدة، ثم يعود إلى الافتراضي بعد durMin دقيقة.
     */
    public static void sendInstant(final Context c, final String targetId,
                                   final org.json.JSONObject cfg, final int durMin, final boolean showResult) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (targetId == null || targetId.isEmpty()) {
            if (showResult) Toast.makeText(c, R.string.dev_pick_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!p.syncConfigured()) {
            if (showResult) Toast.makeText(c, R.string.sync_need_setup, Toast.LENGTH_LONG).show();
            return;
        }
        String b;
        try {
            org.json.JSONObject ins = new org.json.JSONObject(cfg.toString());
            ins.remove("instant");
            ins.remove("rev");
            ins.put("ts", System.currentTimeMillis());
            ins.put("dur", Math.max(1, durMin));
            org.json.JSONObject wrap = new org.json.JSONObject();
            wrap.put("instant", ins);
            b = wrap.toString();
        } catch (Throwable t) { return; }
        final String body = b;
        final String url = configNode(p, targetId);
        new Thread(() -> {
            boolean ok = write(url, "PATCH", body);
            if (showResult) ui.post(() -> Toast.makeText(c,
                    ok ? R.string.instant_sent : R.string.sync_failed, Toast.LENGTH_LONG).show());
        }).start();
    }

    /**
     * إيقاف/تشغيل التطبيق على الجهاز المحدَّد عن بُعد. يُحفظ في عقدة مستقلة (power)
     * حتى لا يمحوه نشر الإعداد الافتراضي.
     */
    public static void setPower(final Context c, final String targetId, final boolean on, final boolean showResult) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (targetId == null || targetId.isEmpty()) {
            if (showResult) Toast.makeText(c, R.string.dev_pick_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!p.syncConfigured()) {
            if (showResult) Toast.makeText(c, R.string.sync_need_setup, Toast.LENGTH_LONG).show();
            return;
        }
        final String url = powerNode(p, targetId);
        final String body = "{\"on\":" + on + ",\"ts\":" + System.currentTimeMillis() + "}";
        new Thread(() -> {
            boolean ok = write(url, "PUT", body);
            if (showResult) ui.post(() -> Toast.makeText(c,
                    !ok ? R.string.sync_failed : on ? R.string.remote_resume_sent : R.string.remote_stop_sent,
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    /** يرسل أمر فتح قفل للجهاز المحدَّد فقط (تحديث حقل unlock دون المساس ببقية الإعداد). */
    public static void unlockTarget(final Context c, final String targetId, final boolean showResult) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (targetId == null || targetId.isEmpty()) {
            if (showResult) Toast.makeText(c, R.string.dev_pick_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (!p.syncConfigured()) return;
        final String url = configNode(p, targetId);
        final String body = "{\"unlock\":" + System.currentTimeMillis() + "}";
        new Thread(() -> {
            boolean ok = write(url, "PATCH", body);     // تحديث جزئي عبر override
            if (showResult) ui.post(() -> Toast.makeText(c,
                    ok ? R.string.remote_unlock_sent : R.string.sync_failed, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** يحضر إعداد الجهاز المحدَّد (لتعبئته في الحقول عند اختياره). */
    public static void fetchTargetConfig(final Context c, final String targetId, final ConfigCallback cb) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (targetId == null || targetId.isEmpty() || !p.syncConfigured()) { ui.post(() -> cb.onResult(null, false)); return; }
        final String url = configNode(p, targetId);
        new Thread(() -> {
            String[] r = read(url);
            org.json.JSONObject o = null;
            boolean ok = "ok".equals(r[1]);
            if (ok && r[0] != null && r[0].trim().length() > 2 && !"null".equals(r[0].trim())) {
                try { o = new org.json.JSONObject(r[0]); } catch (Throwable t) { o = null; }
            }
            final org.json.JSONObject fo = o;
            final boolean fok = ok;
            ui.post(() -> cb.onResult(fo, fok));
        }).start();
    }

    /** يحضر قائمة أجهزة الأطفال المسجّلة. */
    public static void listDevices(final Context c, final DevicesCallback cb) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (!p.syncConfigured()) { ui.post(() -> cb.onResult(new ArrayList<>(), false)); return; }
        final String url = devicesRoot(p) + ".json";
        new Thread(() -> {
            String[] r = read(url);
            boolean ok = "ok".equals(r[1]);
            List<Device> out = new ArrayList<>();
            if (ok && r[0] != null && r[0].trim().length() > 2 && !"null".equals(r[0].trim())) {
                try {
                    org.json.JSONObject root = new org.json.JSONObject(r[0]);
                    java.util.Iterator<String> it = root.keys();
                    while (it.hasNext()) {
                        String id = it.next();
                        org.json.JSONObject dev = root.optJSONObject(id);
                        String name = id; long seen = 0L; boolean off = false;
                        if (dev != null) {
                            org.json.JSONObject meta = dev.optJSONObject("meta");
                            if (meta != null) {
                                name = meta.optString("name", id);
                                seen = meta.optLong("lastSeen", 0L);
                                off = meta.optBoolean("off", false);
                            }
                            org.json.JSONObject pw = dev.optJSONObject("power");   // الأمر الأحدث المرسَل
                            if (pw != null && pw.optLong("ts", 0L) > seen) off = !pw.optBoolean("on", true);
                        }
                        out.add(new Device(id, name, seen, off));
                    }
                } catch (Throwable ignored) { }
            }
            final boolean fok = ok;
            ui.post(() -> cb.onResult(out, fok));
        }).start();
    }

    // ===== HTTP منخفض المستوى =====

    /** @return [body, "ok"|"err"] */
    private static String[] read(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setRequestProperty("Accept", "application/json");
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) return new String[]{null, "err"};
            StringBuilder sb = new StringBuilder();
            try (InputStream in = con.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new String[]{sb.toString(), "ok"};
        } catch (Throwable t) {
            return new String[]{null, "err"};
        } finally {
            if (con != null) try { con.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static boolean write(String url, String method, String body) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            // HttpURLConnection لا يدعم PATCH مباشرة؛ نستخدم POST مع ترويسة override (يدعمها Firebase)
            if ("PATCH".equals(method)) {
                con.setRequestMethod("POST");
                con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            } else {
                con.setRequestMethod(method);
            }
            con.setDoOutput(true);
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = con.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            return false;
        } finally {
            if (con != null) try { con.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
