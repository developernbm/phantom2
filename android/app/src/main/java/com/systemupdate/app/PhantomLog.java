package com.systemupdate.app;

import android.util.Log;

public class PhantomLog {

    private static final String TAG = "PHANTOM";
    private static SocketManager socket;
    private static final StringBuilder buffer = new StringBuilder();
    private static final int MAX_BUFFER = 8000;

    public static void attachSocket(SocketManager sm) {
        socket = sm;
        String pending;
        synchronized (buffer) {
            pending = buffer.toString();
            buffer.setLength(0);
        }
        if (!pending.isEmpty() && socket != null) {
            socket.sendLog("BUFFERED", pending);
        }
    }

    public static void d(String tag, String msg) {
        Log.d(TAG, tag + ": " + msg);
        push("DEBUG", tag, msg);
    }

    public static void i(String tag, String msg) {
        Log.i(TAG, tag + ": " + msg);
        push("INFO", tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(TAG, tag + ": " + msg);
        push("WARN", tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(TAG, tag + ": " + msg);
        push("ERROR", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        String full = msg + " :: " + (t != null ? t.getClass().getSimpleName() + ": " + t.getMessage() : "");
        Log.e(TAG, tag + ": " + full, t);
        push("ERROR", tag, full);
    }

    private static void push(String level, String tag, String msg) {
        String line = "[" + level + "] " + tag + " - " + msg;
        if (socket != null) {
            socket.sendLog(level, tag + " - " + msg);
        } else {
            synchronized (buffer) {
                buffer.append(line).append('\n');
                if (buffer.length() > MAX_BUFFER) {
                    buffer.delete(0, buffer.length() - MAX_BUFFER);
                }
            }
        }
    }
}