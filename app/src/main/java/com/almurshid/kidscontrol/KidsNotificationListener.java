package com.almurshid.kidscontrol;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * خدمة «الوصول إلى الإشعارات». لا تقرأ الإشعارات نفسها، وإنما تمنحنا معرفة
 * أي تطبيق يشغّل فيديو/صوتاً الآن (جلسات الوسائط) — من يوتيوب إلى المتصفح.
 */
public class KidsNotificationListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        MonitorService.ensureRunning(this);
    }

    /** الحزم التي تشغّل وسائط الآن، أو null إذا لم تُمنح الصلاحية. */
    public static Set<String> playingPackages(Context c) {
        try {
            MediaSessionManager msm = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(
                    new ComponentName(c, KidsNotificationListener.class));
            Set<String> out = new HashSet<>();
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st != null && (st.getState() == PlaybackState.STATE_PLAYING
                        || st.getState() == PlaybackState.STATE_BUFFERING)) {
                    out.add(mc.getPackageName());
                }
            }
            return out;
        } catch (SecurityException se) {
            return null; // الصلاحية غير ممنوحة
        } catch (Throwable t) {
            return null;
        }
    }
}
