package com.systemupdate.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

public class DeviceControl {

    private static ComponentName admin(Context ctx) {
        return new ComponentName(ctx, PhantomDeviceAdminReceiver.class);
    }

    public static boolean isAdminActive(Context ctx) {
        try {
            DevicePolicyManager d = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return d != null && d.isAdminActive(admin(ctx));
        } catch (Throwable t) { return false; }
    }

    public static boolean lockNow(Context ctx) {
        try {
            DevicePolicyManager d = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (d != null && d.isAdminActive(admin(ctx))) {
                d.lockNow();
                return true;
            }
        } catch (Throwable t) { }
        return false;
    }
}
