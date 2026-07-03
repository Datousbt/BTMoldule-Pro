package com.datousbt.btmodulepro.util;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志管理器（线程安全）。
 * 配置通过 Config 控制：开关、路径、大小限制。
 */
public class LogManager {

    private static final String TAG = "LogManager";

    // 默认值，会被 Config 覆盖
    private static volatile boolean enabled = false;
    private static volatile String logPath = "/data/data/com.datousbt.btmodulepro/files/btmodulepro.log";
    private static volatile long maxSizeBytes = 1024 * 1024; // 1MB

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final Object lock = new Object();

    public static void configure(boolean enabled, String path, long maxSizeKb) {
        LogManager.enabled = enabled;
        if (path != null && !path.isEmpty()) {
            LogManager.logPath = path;
        }
        LogManager.maxSizeBytes = maxSizeKb * 1024L;
        Log.i(TAG, "日志已配置: enabled=" + enabled + ", path=" + logPath + ", maxSizeKB=" + maxSizeKb);
        // 立即写一条初始化日志确认文件可写
        write("I", TAG, "=== LogManager initialized, enabled=" + enabled + " ===");
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void d(String tag, String msg) {
        write("D", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private static void write(String level, String tag, String msg) {
        if (!enabled) return;

        String line = sdf.format(new Date()) + " [" + level + "] [" + tag + "] " + msg + "\n";

        synchronized (lock) {
            try {
                File f = new File(logPath);
                // 确保父目录存在
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                // 大小限制：超过限制则轮转
                if (f.exists() && f.length() > maxSizeBytes) {
                    File bak = new File(logPath + ".bak");
                    bak.delete();
                    f.renameTo(bak);
                }
                FileWriter fw = new FileWriter(f, true);
                fw.write(line);
                fw.flush();
                fw.close();
            } catch (IOException e) {
                Log.e(TAG, "Log write error", e);
            }
        }
    }
}
