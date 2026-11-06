package com.almurshid.kidscontrol;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * التحديث التلقائي من إصدارات GitHub (مستودع عام، بلا رموز).
 * يقارن رقم الإصدار الحالي بأحدث إصدار، وإن وُجد أحدث يعرض حواراً للتحميل والتثبيت.
 */
public class UpdateChecker {

    // مستودع الإصدارات العام
    private static final String RELEASES_API =
            "https://api.github.com/repos/HassanShamaly/kids-control/releases/latest";

    /** @param userInitiated true إذا ضغط ولي الأمر زر «تحقق من التحديث» (فنُظهر النتيجة دائماً). */
    public static void check(final Activity act, final boolean userInitiated) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String json = httpGet(RELEASES_API);
                String tag = extract(json, "\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
                String apkUrl = extract(json,
                        "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"");
                String verName = tag == null ? "" : tag.replaceFirst("^v", "").replaceFirst("-[0-9]+$", "");
                long latestCode = parseCode(tag);
                long currentCode = currentCode(act);

                if (tag == null || apkUrl == null || latestCode <= 0) {
                    if (userInitiated) ui.post(() ->
                            Toast.makeText(act, R.string.upd_check_failed, Toast.LENGTH_LONG).show());
                    return;
                }
                if (latestCode > currentCode) {
                    final String url = apkUrl, vn = verName;
                    ui.post(() -> promptUpdate(act, vn, url));
                } else if (userInitiated) {
                    ui.post(() ->
                            Toast.makeText(act, R.string.upd_up_to_date, Toast.LENGTH_LONG).show());
                }
            } catch (Throwable t) {
                if (userInitiated) ui.post(() ->
                        Toast.makeText(act, R.string.upd_check_failed, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void promptUpdate(final Activity act, String verName, final String apkUrl) {
        if (act.isFinishing()) return;
        new AlertDialog.Builder(act)
                .setTitle(R.string.upd_title)
                .setMessage(act.getString(R.string.upd_msg, verName))
                .setPositiveButton(R.string.upd_now, (d, w) -> download(act, apkUrl))
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    private static void download(final Activity act, final String apkUrl) {
        final Handler ui = new Handler(Looper.getMainLooper());
        Toast.makeText(act, R.string.upd_downloading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File dir = new File(act.getExternalFilesDir(null), "updates");
                if (!dir.exists()) dir.mkdirs();
                File apk = new File(dir, "kids-control-update.apk");
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(40000);
                c.connect();
                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                c.disconnect();
                ui.post(() -> installApk(act, apk));
            } catch (Throwable t) {
                ui.post(() -> Toast.makeText(act, R.string.upd_download_failed, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static void installApk(Activity act, File apk) {
        // افتح نافذة تثبيت (5 دقائق) حتى لا تحرس خدمة الحماية شاشة التحديث
        new Prefs(act).setInstallUntil(System.currentTimeMillis() + 5 * 60_000L);

        // 1) تأكد من إذن «التثبيت من هذا المصدر»؛ إن لم يكن ممنوحاً افتح شاشته
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(act, R.string.upd_need_install_perm, Toast.LENGTH_LONG).show();
            try {
                act.startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Throwable ignored) { }
            return;
        }

        // 2) ثبّت عبر PackageInstaller حتى نكتشف إن مُنع التثبيت (حماية جوجل/الحظر التلقائي)
        new Thread(() -> {
            try {
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sid = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(sid);
                try (InputStream in = new java.io.FileInputStream(apk);
                     java.io.OutputStream out = session.openWrite("kids", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent si = new Intent(act, UpdateInstallReceiver.class)
                        .setAction(UpdateInstallReceiver.ACTION_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pIntent = PendingIntent.getBroadcast(act, sid, si, flags);
                session.commit(pIntent.getIntentSender());
                session.close();
            } catch (Throwable t) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(act, R.string.upd_install_failed, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ===== أدوات =====
    private static long currentCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** رقم البناء من الوسم بالصيغة vX.Y-CODE. */
    private static long parseCode(String tag) {
        if (tag == null) return -1;
        Matcher m = Pattern.compile("-([0-9]+)$").matcher(tag);
        if (m.find()) { try { return Long.parseLong(m.group(1)); } catch (Exception e) { return -1; } }
        return -1;
    }

    private static String extract(String s, String regex) {
        if (s == null) return null;
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "KidsControl");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } finally {
            c.disconnect();
        }
        return sb.toString();
    }
}
