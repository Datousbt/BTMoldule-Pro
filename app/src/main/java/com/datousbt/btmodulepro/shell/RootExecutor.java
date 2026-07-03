package com.datousbt.btmodulepro.shell;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class RootExecutor {

    private static final String TAG = "RssiTriggerShell";

    /**
     * 以 root 权限执行 shell 命令。
     * 如果命令以 .sh 结尾，视为脚本文件路径，执行 "sh <path>"。
     */
    public static void exec(String command) {
        if (command == null || command.isEmpty()) return;

        try {
            String cmd = command.trim();
            // 如果是指向 .sh 文件，用 sh 执行
            if (cmd.endsWith(".sh")) {
                cmd = "sh " + cmd;
            }

            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // 异步读取输出，避免阻塞
            new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "stdout: " + line);
                    }
                } catch (Exception ignored) {
                }
            }).start();
            new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.e(TAG, "stderr: " + line);
                    }
                } catch (Exception ignored) {
                }
            }).start();

            Log.i(TAG, "exec: " + cmd);
        } catch (Throwable t) {
            Log.e(TAG, "exec error: " + t.getMessage(), t);
        }
    }
}
