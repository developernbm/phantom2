package com.systemupdate.app;

import android.content.Context;
import android.content.SharedPreferences;

public class DeviceStore {

    private static final String PREFS = "sys_update_prefs";
    private static final String KEY_SETUP_DONE = "setup_complete";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSetupComplete(Context c) {
        return prefs(c).getBoolean(KEY_SETUP_DONE, false);
    }

    public static void setSetupComplete(Context c, boolean complete) {
        prefs(c).edit().putBoolean(KEY_SETUP_DONE, complete).apply();
    }
}
