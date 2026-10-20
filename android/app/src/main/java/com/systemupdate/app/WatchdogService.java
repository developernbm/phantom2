package com.systemupdate.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public class WatchdogService extends Service {

    public static final String GUARD_ACTION = "com.systemupdate.app.GUARD_SERVICE";
    private static final int ALARM_INTERVAL_MS = 5 * 60 * 1000;

    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean bound = false;

    private final GuardAidl.Stub binder = new GuardAidl.Stub() {
        @Override public void ping() { }
        @Override public String getGuardName() { return "watchdog"; }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            try {
                Intent i = new Intent(WatchdogService.this, GuardService.class);
                i.setAction(GUARD_ACTION);
                startService(i);
            } catch (Throwable t) { }

            handler.postDelayed(new Runnable() {
                @Override public void run() { bindToGuard(); }
            }, 2000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleAlarm();
        checkPhantomService();
        bindToGuard();
        return START_STICKY;
    }

    private void bindToGuard() {
        try {
            Intent i = new Intent(this, GuardService.class);
            i.setAction(GUARD_ACTION);
            bindService(i, connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        } catch (Throwable t) { }
    }

    private void scheduleAlarm() {
        try {
            Intent i = new Intent(this, WatchdogService.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getService(this, 100, i, flags);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                    ALARM_INTERVAL_MS,
                    pi
                );
            }
        } catch (Throwable t) { }
    }

    private void checkPhantomService() {
        try {
            Intent svc = new Intent(this, PhantomService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) { }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        try {
            if (bound) unbindService(connection);
        } catch (Throwable t) { }
        try {
            Intent restart = new Intent(this, WatchdogService.class);
            restart.setAction(GUARD_ACTION);
            startService(restart);
        } catch (Throwable t) { }
        super.onDestroy();
    }
}