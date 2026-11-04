package com.almurshid.kidscontrol;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/** مسؤول الجهاز: ما دام مفعّلاً لا يمكن حذف التطبيق. */
public class KidsAdminReceiver extends DeviceAdminReceiver {
    @Override
    public CharSequence onDisableRequested(@NonNull Context context, @NonNull Intent intent) {
        return context.getString(R.string.admin_disable_warning);
    }
}
