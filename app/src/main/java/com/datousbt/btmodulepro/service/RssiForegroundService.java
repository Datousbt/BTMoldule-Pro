package com.datousbt.btmodulepro.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.datousbt.btmodulepro.R;
import com.datousbt.btmodulepro.hook.RssiTriggerEngine;
import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.model.TriggerRule;
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.datousbt.btmodulepro.ui.MainActivity;
import com.datousbt.btmodulepro.util.LogManager;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RssiForegroundService extends Service {

    private static final String TAG = "RssiService";
    private static final String CHANNEL_ID = "rssi_monitor";
    private static final int NOTIFY_ID = 1;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner leScanner;
    private RssiTriggerEngine engine;
    private Config config;
    private HandlerThread worker;
    private Handler handler;
    private boolean running;

    // BLE 扫描结果缓存
    private final Map<String, Integer> rssiCache = new LinkedHashMap<>();
    private final Map<String, Long> rssiTimeCache = new LinkedHashMap<>();
    private final Map<String, String> nameCache = new LinkedHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    // ==================== 启动/停止 ====================

    private void start() {
        running = true;
        btAdapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (btAdapter == null) { stopSelf(); return; }
        leScanner = btAdapter.getBluetoothLeScanner();

        config = ConfigManager.load(this);
        engine = new RssiTriggerEngine(config);
        LogManager.configure(config.logEnabled, config.logPath, config.logMaxSizeKb);
        LogManager.i(TAG, "Service started. rules=" + config.rules.size());

        // 前台通知
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("蓝牙触发器运行中")
                .setContentText("BLE 扫描监听信号强度")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFY_ID, n);

        // 后台线程
        worker = new HandlerThread("RssiWorker");
        worker.start();
        handler = new Handler(worker.getLooper());

        // 启动 BLE 扫描
        startBleScan();

        // 定时热加载配置 + 写状态文件
        handler.post(configReloader);
    }

    private void stop() {
        running = false;
        stopBleScan();
        if (handler != null) handler.removeCallbacks(configReloader);
        if (worker != null) worker.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        LogManager.i(TAG, "Service stopped");
    }

    // ==================== BLE 扫描（核心） ====================

    private void startBleScan() {
        if (leScanner == null) {
            Log.e(TAG, "BLE scanner not available");
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)  // 高频率扫描
                .setReportDelay(0)  // 立即上报
                .build();

        // 不加 filter，扫描所有设备（根据 MAC 在回调里过滤）
        List<ScanFilter> filters = new ArrayList<>();
        // 可选：为每个规则的目标设备加 MAC 过滤，减少无关回调
        for (TriggerRule rule : config.rules) {
            if (rule.enable && rule.mac != null && !rule.mac.isEmpty()) {
                try {
                    filters.add(new ScanFilter.Builder()
                            .setDeviceAddress(rule.mac)
                            .build());
                } catch (Exception ignored) {}
            }
        }
        if (filters.isEmpty()) filters = null;

        LogManager.i(TAG, "Starting BLE scan, filters=" + (filters != null ? filters.size() : 0));
        Log.i(TAG, "BLE scan started");

        try {
            leScanner.startScan(filters, settings, scanCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Missing BLE scan permission", e);
        }
    }

    private void stopBleScan() {
        if (leScanner != null) {
            try { leScanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        Log.i(TAG, "BLE scan stopped");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();
            if (device == null) return;

            String mac = device.getAddress();
            String name = device.getName();

            if (name != null) nameCache.put(mac, name);

            Integer old = rssiCache.put(mac, rssi);
            rssiTimeCache.put(mac, System.currentTimeMillis());
            if (old != null && old == rssi) return;

            LogManager.d(TAG, "RSSI: " + mac + " = " + rssi);

            // 触发规则
            if (engine != null) engine.evaluate(mac, name != null ? name : "", rssi);

            // 写状态文件（每 1.5 秒最多一次，减少 IO）
            writeStatusThrottled();
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE scan failed: " + errorCode);
            // 1 秒后重试
            handler.postDelayed(() -> {
                if (running) startBleScan();
            }, 1000);
        }
    };

    // ==================== 配置热加载 ====================

    private final Runnable configReloader = new Runnable() {
        public void run() {
            if (!running) return;

            Config newCfg = ConfigManager.load(RssiForegroundService.this);
            if (config.logEnabled != newCfg.logEnabled ||
                    config.rules.size() != newCfg.rules.size() ||
                    config.rssiMode != newCfg.rssiMode) {
                LogManager.configure(newCfg.logEnabled, newCfg.logPath, newCfg.logMaxSizeKb);
                // 规则变了，重启扫描以更新 filter
                if (rulesChanged(config, newCfg)) {
                    stopBleScan();
                    startBleScan();
                }
            }
            config = newCfg;
            engine.config = config;

            handler.postDelayed(this, 5000);
        }
    };

    private boolean rulesChanged(Config old, Config nw) {
        if (old.rules.size() != nw.rules.size()) return true;
        for (int i = 0; i < old.rules.size(); i++) {
            TriggerRule a = old.rules.get(i), b = nw.rules.get(i);
            if (!a.mac.equals(b.mac) || a.enable != b.enable) return true;
        }
        return false;
    }

    // ==================== 状态文件（去抖写） ====================

    private long lastStatusWrite;

    private void writeStatusThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastStatusWrite < 1500) return;
        lastStatusWrite = now;
        writeStatus();
    }

    private void writeStatus() {
        try {
            StringBuilder sb = new StringBuilder("{\"devices\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : rssiCache.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                String mac = e.getKey();
                sb.append("\"").append(mac).append("\":{");
                sb.append("\"name\":\"").append(esc(nameCache.getOrDefault(mac, ""))).append("\",");
                sb.append("\"rssi\":").append(e.getValue()).append(",");
                sb.append("\"time\":").append(rssiTimeCache.getOrDefault(mac, 0L)).append("}");
            }
            sb.append("}}");
            FileWriter fw = new FileWriter(new java.io.File(getFilesDir(), "rssi_status.json"));
            fw.write(sb.toString());
            fw.flush();
            fw.close();
        } catch (Exception ignored) {}
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "蓝牙信号监听", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("BLE 扫描 RSSI 信号强度");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
