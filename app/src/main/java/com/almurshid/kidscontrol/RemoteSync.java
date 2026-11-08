package com.almurshid.kidscontrol;

import android.content.Context;
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

/**
 * مزامنة الإعدادات عبر Firebase Realtime Database باستخدام واجهة REST مباشرة
 * (بدون مكتبة Firebase، فلا يتغيّر نظام البناء). جهاز ولي الأمر ينشر (PUT)،
 * وأجهزة الأطفال تقرأ (GET) وتطبّق. كل العمليات دفاعية: أي خطأ شبكة يُتجاهل بهدوء.
 */
public class RemoteSync {

    private static volatile long lastPullTs = 0L;

    /**
     * على جهاز الطفل: إن حمل الإعداد أمر فتح قفل (unlock) أحدث مما رُئي سابقاً وكان حديثاً
     * (خلال 15 دقيقة)، ننفّذه مرة واحدة عبر خدمة المراقبة. يمنع التكرار بحفظ آخر طابع رُئي.
     */
    private static void maybeRemoteUnlock(Context c, Prefs p, org.json.JSONObject o) {
        try {
            if (!p.isChild()) return;
            long unlock = o.optLong("unlock", 0L);
            if (unlock <= 0) return;
            long now = System.currentTimeMillis();
            if (unlock <= p.lastUnlockSeen()) return;        // نُفّذ سابقاً
            p.setLastUnlockSeen(unlock);
            if (now - unlock > 15 * 60_000L) return;         // قديم: تجاهله (لجهاز أُضيف حديثاً)
            c.startService(new android.content.Intent(c, MonitorService.class)
                    .setAction(MonitorService.ACTION_REMOTE_UNLOCK));
        } catch (Throwable ignored) { }
    }

    private static String node(Prefs p) {
        String base = p.syncUrl();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String code;
        try { code = URLEncoder.encode(p.familyCode(), "UTF-8"); }
        catch (Exception e) { code = p.familyCode(); }
        return base + "/kc/" + code + ".json";
    }

    /** جهاز ولي الأمر: ينشر الإعدادات الحالية. */
    public static void publish(final Context c) {
        final Prefs p = new Prefs(c);
        if (!p.isParent() || !p.syncConfigured()) return;
        final String url = node(p);
        final String body = p.exportConfig().toString();
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestMethod("PUT");
                con.setDoOutput(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                con.getResponseCode();
            } catch (Throwable ignored) {
            } finally {
                if (con != null) try { con.disconnect(); } catch (Throwable ignored) { }
            }
        }).start();
    }

    /** جهاز ولي الأمر: ينشر مع إظهار نتيجة للمستخدم. */
    public static void publishWithResult(final Context c) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (!p.syncConfigured()) {
            Toast.makeText(c, R.string.sync_need_setup, Toast.LENGTH_LONG).show();
            return;
        }
        final String url = node(p);
        final String body = p.exportConfig().toString();
        new Thread(() -> {
            HttpURLConnection con = null;
            boolean ok = false;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setRequestMethod("PUT");
                con.setDoOutput(true);
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = con.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                int code = con.getResponseCode();
                ok = code >= 200 && code < 300;
            } catch (Throwable ignored) {
            } finally {
                if (con != null) try { con.disconnect(); } catch (Throwable ignored) { }
            }
            final boolean fok = ok;
            ui.post(() -> Toast.makeText(c,
                    fok ? R.string.sync_published : R.string.sync_failed,
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    /** جهاز الطفل: يسحب الإعدادات إن مرّ وقت كافٍ منذ آخر سحب (يُستدعى من حلقة المراقبة). */
    public static void pullIfDue(Context c, long minIntervalMs) {
        Prefs p = new Prefs(c);
        if (!p.isChild() || !p.syncConfigured()) return;
        long now = System.currentTimeMillis();
        if (now - lastPullTs < minIntervalMs) return;
        lastPullTs = now;
        pull(c, false);
    }

    /** سحب فوري (يُستدعى عند فتح التطبيق أو بزر «مزامنة الآن»). */
    public static void pull(final Context c, final boolean showResult) {
        final Prefs p = new Prefs(c);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (!p.syncConfigured()) {
            if (showResult) Toast.makeText(c, R.string.sync_need_setup, Toast.LENGTH_LONG).show();
            return;
        }
        final String url = node(p);
        new Thread(() -> {
            HttpURLConnection con = null;
            String body = null;
            boolean httpOk = false;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                con.setRequestProperty("Accept", "application/json");
                int code = con.getResponseCode();
                httpOk = code >= 200 && code < 300;
                if (httpOk) {
                    StringBuilder sb = new StringBuilder();
                    try (InputStream in = con.getInputStream();
                         BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line);
                    }
                    body = sb.toString();
                }
            } catch (Throwable ignored) {
            } finally {
                if (con != null) try { con.disconnect(); } catch (Throwable ignored) { }
            }
            final String fbody = body;
            final boolean fhttpOk = httpOk;
            boolean hasData = fbody != null && fbody.trim().length() > 2 && !"null".equals(fbody.trim());
            boolean applied = false;
            if (hasData) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(fbody);
                    p.applyConfig(o);
                    p.setLastPull(System.currentTimeMillis());
                    applied = true;
                    maybeRemoteUnlock(c, p, o);
                } catch (Throwable ignored) { }
            }
            final boolean fapplied = applied;
            final boolean fempty = fhttpOk && !hasData; // اتصل بنجاح لكن لا توجد إعدادات منشورة
            if (showResult) ui.post(() -> Toast.makeText(c,
                    fapplied ? R.string.sync_pulled
                             : fempty ? R.string.sync_empty
                                      : R.string.sync_failed,
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}
