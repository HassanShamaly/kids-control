package com.almurshid.kidscontrol;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/**
 * تطبيق الإعداد «الافتراضي» و«اللحظي» على إعدادات جهاز.
 * يُستخدم على أجهزة الأطفال (عند الاستلام من ولي الأمر) وعلى جوال ولي الأمر (عند اختياره كهدف).
 *
 *  - الافتراضي: يُحفظ كأساس ويُطبَّق فوراً، إلا إذا كان هناك إعداد لحظي سارٍ
 *    (فيُطبَّق الأساس الجديد تلقائياً عند انتهاء اللحظي).
 *  - اللحظي: يُطبَّق الآن مع جلسة وقت جديدة، وينتهي في وقت محدد ثم يعود الأساس.
 */
public final class ConfigApplier {

    private ConfigApplier() { }

    public static void applyDefault(Prefs p, JSONObject cfg) {
        if (cfg == null) return;
        p.setBaseConfigJson(cfg.toString());
        if (p.instantUntil() > System.currentTimeMillis()) return; // لحظي سارٍ: يُطبَّق الأساس عند انتهائه
        p.applyConfig(cfg);
    }

    /**
     * @param until نهاية الإعداد اللحظي (طابع زمني)
     * @param freshSession يمنح جلسة وقت جديدة الآن (ويفكّ أي قفل/استراحة) — يتطلب أن تكون المراقبة شغّالة
     */
    public static void applyInstant(Context c, Prefs p, JSONObject cfg, long until, boolean freshSession) {
        if (cfg == null) return;
        long now = System.currentTimeMillis();
        if (until <= now) return; // انتهت مدته قبل أن يصل
        // إن لم يصل افتراضي من قبل، احفظ الإعداد الحالي كأساس يُعاد إليه
        if (p.baseConfigJson().isEmpty()) p.setBaseConfigJson(p.exportConfig().toString());
        p.applyConfig(cfg);
        p.setInstantUntil(until);
        if (freshSession && p.enabled()) {
            try {
                c.startService(new Intent(c, MonitorService.class)
                        .setAction(MonitorService.ACTION_REMOTE_UNLOCK));
            } catch (Throwable ignored) { }
        }
    }

    /** يُستدعى دورياً من خدمة المراقبة: عند انتهاء اللحظي يعيد الإعداد الأساسي. */
    public static void checkInstantExpiry(Prefs p, long now) {
        long u = p.instantUntil();
        if (u <= 0 || now < u) return;
        p.setInstantUntil(0L);
        String base = p.baseConfigJson();
        if (base.isEmpty()) return;
        try { p.applyConfig(new JSONObject(base)); } catch (Throwable ignored) { }
    }

    /** الإعداد «الافتراضي» لهذا الجهاز للتحرير (الأساس إن كان هناك لحظي سارٍ). */
    public static JSONObject editableDefault(Prefs p) {
        if (p.instantUntil() > System.currentTimeMillis() && !p.baseConfigJson().isEmpty()) {
            try { return new JSONObject(p.baseConfigJson()); } catch (Throwable ignored) { }
        }
        return p.exportConfig();
    }
}
