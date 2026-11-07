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
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                con.setRequestProperty("Accept", "application/json");
                int code = con.getResponseCode();
                if (code >= 200 && code < 300) {
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
            boolean applied = false;
            if (fbody != null && fbody.length() > 2 && !"null".equals(fbody)) {
                try {
                    p.applyConfig(new org.json.JSONObject(fbody));
                    p.setLastPull(System.currentTimeMillis());
                    applied = true;
                } catch (Throwable ignored) { }
            }
            final boolean fapplied = applied;
            if (showResult) ui.post(() -> Toast.makeText(c,
                    fapplied ? R.string.sync_pulled : R.string.sync_failed,
                    Toast.LENGTH_LONG).show());
        }).start();
    }
}
