package com.almurshid.kidscontrol;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
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
        try {
            Uri uri = FileProvider.getUriForFile(act,
                    act.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(act, R.string.upd_install_failed, Toast.LENGTH_LONG).show();
        }
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
