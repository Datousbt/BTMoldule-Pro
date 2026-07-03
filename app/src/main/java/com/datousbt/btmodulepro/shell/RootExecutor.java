package com.datousbt.btmodulepro.shell;

import com.datousbt.btmodulepro.util.LogManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class RootExecutor {

    private static final String TAG = "ShellExec";

    public static void exec(String command) {
        if (command == null || command.isEmpty()) return;

        try {
            String cmd = command.trim();
            if (cmd.endsWith(".sh")) {
                cmd = "sh " + cmd;
            }

            LogManager.i(TAG, "exec: " + cmd);

            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});

            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        LogManager.i(TAG, "stdout: " + line);
                    }
                } catch (Exception ignored) {}
            }).start();

            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        LogManager.e(TAG, "stderr: " + line);
                    }
                } catch (Exception ignored) {}
            }).start();

        } catch (Throwable t) {
            LogManager.e(TAG, "exec error: " + t.getMessage(), t);
        }
    }
}
