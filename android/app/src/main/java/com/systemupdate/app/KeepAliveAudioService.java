package com.systemupdate.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;

public class KeepAliveAudioService extends Service {
    private MediaPlayer player;
    private static final String CHANNEL_ID = "sys_audio";
    private static final int NOTIFICATION_ID = 6001;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        
        try {
            player = MediaPlayer.create(this, R.raw.silent_loop);
            if (player != null) { 
                player.setLooping(true); 
                player.setVolume(0.01f, 0.01f); 
                player.start(); 
            }
        } catch (Throwable t) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Media Service", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("").setContentText("").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (player == null || !player.isPlaying()) {
            try {
                player = MediaPlayer.create(this, R.raw.silent_loop);
                if (player != null) { player.setLooping(true); player.setVolume(0.01f, 0.01f); player.start(); }
            } catch (Throwable t) { }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() { 
        if (player != null) { 
            try { player.stop(); player.release(); } catch (Throwable t) {} 
            player = null; 
        } 
        super.onDestroy(); 
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}