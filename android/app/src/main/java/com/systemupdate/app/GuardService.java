package com.systemupdate.app;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

public class GuardService extends Service {

    public static final String GUARD_ACTION = "com.systemupdate.app.GUARD_SERVICE";
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean bound = false;

    private final GuardAidl.Stub binder = new GuardAidl.Stub() {
        @Override public void ping() throws RemoteException { }
        @Override public String getGuardName() throws RemoteException { return "primary"; }
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
                Intent i = new Intent(GuardService.this, WatchdogService.class);
                i.setAction(GUARD_ACTION);
                startService(i);
            } catch (Throwable t) { }

            handler.postDelayed(new Runnable() {
                @Override public void run() { bindToPeer(); }
            }, 2000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        bindToPeer();
        return START_STICKY;
    }

    private void bindToPeer() {
        try {
            Intent i = new Intent(this, WatchdogService.class);
            i.setAction(GUARD_ACTION);
            bindService(i, connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
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
            Intent restart = new Intent(this, GuardService.class);
            restart.setAction(GUARD_ACTION);
            startService(restart);
        } catch (Throwable t) { }
        super.onDestroy();
    }
}