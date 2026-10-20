package com.systemupdate.app;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class NotificationListenerBridge extends NotificationListenerService {
    private static NotificationListenerBridge instance;
    public static NotificationListenerBridge getInstance() { return instance; }

    @Override public void onListenerConnected() { instance = this; }
    @Override public void onListenerDisconnected() { instance = null; }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { }
}
