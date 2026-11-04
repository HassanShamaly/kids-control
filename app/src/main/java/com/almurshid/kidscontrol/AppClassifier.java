package com.almurshid.kidscontrol;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * تحديد فئة التطبيق المفتوح: فيديو، لعبة، أو غير ذلك.
 *
 * الفيديو:
 *  - قائمة معروفة (يوتيوب، تيك توك، إنستغرام، سناب، نتفليكس، شاهد، ...)
 *  - أي تطبيق مصنّف «فيديو» في النظام
 *  - أي تطبيق مفتوح أمام الطفل ويشغّل وسائط الآن (يشمل المتصفح وغيره)
 * الألعاب:
 *  - تصنيف «لعبة» في النظام
 *  - أو وجود محرّك ألعاب داخل التطبيق (Unity, Unreal, Cocos, Godot, ...)
 * ولولي الأمر أن يضيف أو يستثني أي تطبيق يدوياً.
 */
public class AppClassifier {

    public static final int CAT_NONE = 0;
    public static final int CAT_YT   = 1;   // فيديو (الاسم قديم، المعنى: كل الفيديو)
    public static final int CAT_GAME = 2;

    /** تطبيقات فيديو معروفة. */
    private static final Set<String> VIDEO = new HashSet<>();
    static {
        String[] v = {
            // يوتيوب وبدائله
            "com.google.android.youtube", "com.google.android.apps.youtube.kids",
            "com.google.android.apps.youtube.music", "com.google.android.youtube.tv",
            "com.vanced.android.youtube", "app.revanced.android.youtube", "org.schabi.newpipe",
            // مقاطع قصيرة وتواصل
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.aweme", "com.instagram.android", "com.snapchat.android",
            "com.facebook.katana", "com.facebook.lite", "video.like", "com.kwai.video",
            "com.kwai.bulldog", "sg.bigo.live", "tv.twitch.android.app", "com.kick.mobile",
            "com.dailymotion.dailymotion", "com.vimeo.android.videoapp",
            // منصات مشاهدة
            "com.netflix.mediaclient", "net.mbc.shahid", "com.disney.disneyplus",
            "com.amazon.avod.thirdpartyclient", "com.samsung.android.tvplus",
            "com.osn.go", "com.starzplayarabia.android", "com.watchit.vod",
            // مشغّلات
            "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
        };
        for (String s : v) VIDEO.add(s);
    }

    /** تطبيقات لا تُحسب أبداً كفيديو حتى لو شغّلت صوتاً (مكالمات، نظام). */
    private static final Set<String> NEVER = new HashSet<>();
    static {
        String[] n = {
            "com.android.systemui", "com.android.server.telecom", "com.android.phone",
            "com.samsung.android.incallui", "com.samsung.android.dialer",
            "com.google.android.dialer", "com.android.dialer",
            "com.android.settings", "com.sec.android.app.camera",
            // مساعد جوجل والبحث الصوتي — يشغّلان صوتاً فلا يصح احتسابهما فيديو
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googleassistant",
            "com.samsung.android.bixby.agent", "com.samsung.android.visionintelligence",
            "com.google.android.tts", "com.google.android.apps.messaging",
        };
        for (String s : n) NEVER.add(s);
    }

    /** علامات محرّكات الألعاب داخل ملف التطبيق. */
    private static final String[] ENGINE_LIBS = {
        "libunity", "libil2cpp", "libmonobdwgc", "libue4", "libunreal", "libcocos",
        "libgodot", "libgdx", "libyoyo", "libsdl2", "libdmengine", "libcorona",
        "libminecraftpe", "libroblox", "libfmod", "libgame.so", "libsupercell",
    };

    private final Context ctx;
    private final PackageManager pm;
    private final Prefs prefs;
    private final Map<String, Boolean> engineCache = new HashMap<>();
    private Set<String> launchers;

    public AppClassifier(Context c) {
        ctx = c.getApplicationContext();
        pm = ctx.getPackageManager();
        prefs = new Prefs(ctx);
    }

    /**
     * @param playing  حزم التطبيقات التي تشغّل وسائط الآن (null إذا لا نملك صلاحية معرفتها)
     * @param musicActive  هل يوجد صوت وسائط يعمل على الجهاز الآن
     */
    public int categoryOf(String pkg, Set<String> playing, boolean musicActive) {
        if (pkg == null || pkg.isEmpty()) return CAT_NONE;
        if (pkg.equals(ctx.getPackageName())) return CAT_NONE;
        if (prefs.getSet(Prefs.K_ALLOWED).contains(pkg)) return CAT_NONE;
        if (NEVER.contains(pkg) || isLauncher(pkg)) return CAT_NONE;

        if (isGame(pkg)) return CAT_GAME;
        if (isVideoApp(pkg)) return CAT_YT;

        // كشف ديناميكي: التطبيق المفتوح يشغّل فيديو/وسائط الآن
        if (prefs.getSet(Prefs.K_EXC_VIDEO).contains(pkg)) return CAT_NONE;
        if (isAudioApp(pkg)) return CAT_NONE; // تطبيقات الصوت فقط (موسيقى/قرآن) لا تُحسب
        if (playing != null) {
            if (playing.contains(pkg)) return CAT_YT;
        } else if (musicActive) {
            return CAT_YT; // بلا صلاحية الإشعارات: أي صوت وسائط والتطبيق مفتوح
        }
        return CAT_NONE;
    }

    // ===== الفيديو =====
    public boolean autoVideo(String pkg) {
        if (VIDEO.contains(pkg)) return true;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai.category == ApplicationInfo.CATEGORY_VIDEO;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isVideoApp(String pkg) {
        if (prefs.getSet(Prefs.K_EXC_VIDEO).contains(pkg)) return false;
        return prefs.getSet(Prefs.K_INC_VIDEO).contains(pkg) || autoVideo(pkg);
    }

    /**
     * أي تطبيق يشغّل وسائط الآن ويجب احتسابه فيديو (يشمل ريلز/شورتس حتى لو لم
     * يُكتشف التطبيق في المقدمة لحظياً). يستثني تطبيقات الصوت والمسموحة والنظام.
     */
    public String videoPlaying(Set<String> playing) {
        if (playing == null) return null;
        Set<String> allowed = prefs.getSet(Prefs.K_ALLOWED);
        for (String p : playing) {
            if (p == null || p.equals(ctx.getPackageName())) continue;
            if (allowed.contains(p) || NEVER.contains(p)) continue;
            if (prefs.getSet(Prefs.K_EXC_VIDEO).contains(p)) continue;
            if (isLauncher(p) || isAudioApp(p)) continue;
            return p;
        }
        return null;
    }

    private boolean isAudioApp(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai.category == ApplicationInfo.CATEGORY_AUDIO;
        } catch (Exception e) {
            return false;
        }
    }

    // ===== الألعاب =====
    public boolean autoGame(String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                return false; // تطبيقات النظام الأساسية ليست ألعاباً
            }
            if (ai.category == ApplicationInfo.CATEGORY_GAME) return true;
            //noinspection deprecation
            if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0) return true;
            return hasGameEngine(pkg, ai);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isGame(String pkg) {
        if (prefs.getSet(Prefs.K_EXC_GAME).contains(pkg)) return false;
        return prefs.getSet(Prefs.K_INC_GAME).contains(pkg) || autoGame(pkg);
    }

    private boolean hasGameEngine(String pkg, ApplicationInfo ai) {
        Boolean cached;
        synchronized (engineCache) { cached = engineCache.get(pkg); }
        if (cached != null) return cached;

        boolean found = scanApk(ai.sourceDir);
        if (!found && ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) {
                if (scanApk(s)) { found = true; break; }
            }
        }
        synchronized (engineCache) { engineCache.put(pkg, found); }
        return found;
    }

    private static boolean scanApk(String path) {
        if (path == null) return false;
        ZipFile z = null;
        try {
            z = new ZipFile(path);
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toLowerCase(Locale.US);
                if (n.startsWith("assets/bin/data/")) return true; // Unity
                if (!n.startsWith("lib/") || !n.endsWith(".so")) continue;
                String base = n.substring(n.lastIndexOf('/') + 1);
                if (base.equals("libg.so")) return true; // Supercell
                for (String m : ENGINE_LIBS) {
                    if (base.startsWith(m) || base.equals(m)) return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (z != null) try { z.close(); } catch (Exception ignored) { }
        }
        return false;
    }

    // ===== الشاشة الرئيسية =====
    private boolean isLauncher(String pkg) {
        if (launchers == null) {
            launchers = new HashSet<>();
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
                for (ResolveInfo r : l) launchers.add(r.activityInfo.packageName);
            } catch (Exception ignored) { }
        }
        return launchers.contains(pkg);
    }
}
