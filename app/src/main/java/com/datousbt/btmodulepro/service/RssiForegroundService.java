package com.datousbt.btmodulepro.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.datousbt.btmodulepro.R;
import com.datousbt.btmodulepro.hook.RssiTriggerEngine;
import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.model.TriggerRule;
import com.datousbt.btmodulepro.shell.RootExecutor;
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.datousbt.btmodulepro.ui.MainActivity;
import com.datousbt.btmodulepro.util.LogManager;

import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RssiForegroundService extends Service {

    private static final String TAG = "RssiService";
    private static final String CHANNEL_ID = "rssi_monitor";
    private static final int NOTIFY_ID = 1;

    private BluetoothAdapter btAdapter;
    private BluetoothManager btManager;
    private RssiTriggerEngine engine;
    private Config config;
    private HandlerThread workerThread;
    private Handler worker;
    private boolean running;

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

    // ==================== 启动 / 停止 ====================

    private void start() {
        running = true;
        btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = btManager.getAdapter();
        if (btAdapter == null) {
            Log.e(TAG, "Bluetooth not available, stopping");
            stopSelf();
            return;
        }

        config = ConfigManager.load(this);
        engine = new RssiTriggerEngine(config);
        LogManager.configure(config.logEnabled, config.logPath, config.logMaxSizeKb);
        LogManager.i(TAG, "Service started. rules=" + config.rules.size() +
                " mode=" + config.rssiMode + " poll=" + config.pollInterval + "ms");
        Log.i(TAG, "Service started. rules=" + config.rules.size());

        workerThread = new HandlerThread("RssiWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        // 前台通知
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("蓝牙触发器运行中")
                .setContentText("监听蓝牙信号强度")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIFY_ID, n);

        // 蓝牙连接广播
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(btReceiver, f);

        // RSSI 轮询
        int interval = config.pollInterval > 0 ? config.pollInterval : 5000;
        worker.postDelayed(pollRunnable, 1000); // 1秒后首次轮询
        Log.i(TAG, "Poll interval=" + interval + "ms");
    }

    private void stop() {
        running = false;
        if (worker != null) worker.removeCallbacks(pollRunnable);
        if (workerThread != null) workerThread.quitSafely();
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        stopForeground(STOP_FOREGROUND_REMOVE);
        LogManager.i(TAG, "Service stopped");
    }

    // ==================== 蓝牙广播 ====================

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        public void onReceive(Context ctx, Intent intent) {
            BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (dev == null) return;
            String mac = dev.getAddress();
            String name = dev.getName();

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                Log.i(TAG, "ACL connected: " + mac);
                nameCache.put(mac, name != null ? name : "");
                // 连接时立刻查 RSSI
                queryRssi(dev);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) {
                Log.i(TAG, "ACL disconnected: " + mac);
                rssiCache.remove(mac);
                rssiTimeCache.put(mac, System.currentTimeMillis());
                // 断开视为 RSSI = -100（极弱信号）
                if (engine != null)
                    engine.evaluate(mac, nameCache.getOrDefault(mac, ""), -100);
                writeStatus();
            }
        }
    };

    // ==================== RSSI 轮询 ====================

    private final Runnable pollRunnable = new Runnable() {
        public void run() {
            if (!running || !btAdapter.isEnabled()) {
                worker.postDelayed(this, 5000);
                return;
            }

            // 热加载配置
            Config newCfg = ConfigManager.load(RssiForegroundService.this);
            if (config.rules.size() != newCfg.rules.size() ||
                    config.rssiMode != newCfg.rssiMode ||
                    config.logEnabled != newCfg.logEnabled) {
                LogManager.configure(newCfg.logEnabled, newCfg.logPath, newCfg.logMaxSizeKb);
                LogManager.i(TAG, "Config reloaded");
            }
            config = newCfg;
            engine.config = config;

            // 获取已连接设备并查询 RSSI
            pollConnectedDevices();

            int interval = config.pollInterval > 0 ? config.pollInterval : 5000;
            worker.postDelayed(this, interval);
        }
    };

    private void pollConnectedDevices() {
        Set<BluetoothDevice> devices = new HashSet<>();

        // API 31+: 公开 API 获取已连接设备
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { devices.addAll(btManager.getConnectedDevices(BluetoothProfile.GATT)); }
            catch (SecurityException ignored) {}
            try { devices.addAll(btManager.getConnectedDevices(BluetoothProfile.A2DP)); }
            catch (SecurityException ignored) {}
            try { devices.addAll(btManager.getConnectedDevices(BluetoothProfile.HEADSET)); }
            catch (SecurityException ignored) {}
        }

        // 备用: 检查所有已配对设备中哪些是连接的
        try {
            for (BluetoothDevice d : btAdapter.getBondedDevices()) {
                if (btAdapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
                        && d.getAddress().equals(getActiveDevice(BluetoothProfile.A2DP))) {
                    devices.add(d);
                }
            }
        } catch (SecurityException ignored) {}

        // 备用: 反射 getMostRecentlyConnectedDevices
        try {
            Method m = btAdapter.getClass().getMethod("getMostRecentlyConnectedDevices");
            @SuppressWarnings("unchecked")
            List<BluetoothDevice> list = (List<BluetoothDevice>) m.invoke(btAdapter);
            if (list != null) devices.addAll(list);
        } catch (Exception ignored) {}

        // 对每个已连接设备查询 RSSI
        for (BluetoothDevice d : devices) {
            String mac = d.getAddress();
            // 只查询配置了规则的设备
            boolean interested = false;
            for (TriggerRule r : config.rules) {
                if (r.enable && mac.equalsIgnoreCase(r.mac)) { interested = true; break; }
            }
            if (!interested) continue;

            String name = d.getName();
            if (name != null) nameCache.put(mac, name);
            queryRssi(d);
        }

        writeStatus();
    }

    private String getActiveDevice(int profile) {
        try {
            Method m = btAdapter.getClass().getMethod("getActiveDevice", int.class);
            BluetoothDevice d = (BluetoothDevice) m.invoke(btAdapter, profile);
            return d != null ? d.getAddress() : null;
        } catch (Exception e) { return null; }
    }

    private void queryRssi(BluetoothDevice device) {
        // 方案1: 反射 getRssi() —— 隐藏API，多数HyperOS/小米ROM可用
        try {
            Method m = BluetoothDevice.class.getMethod("getRssi");
            Integer rssi = (Integer) m.invoke(device);
            if (rssi != null && rssi < 0 && rssi >= -100) {
                handleRssi(device, rssi);
                return;
            }
        } catch (Exception ignored) {}

        // 方案2: 反射 mRssi 字段
        try {
            java.lang.reflect.Field f = BluetoothDevice.class.getDeclaredField("mRssi");
            f.setAccessible(true);
            Short rssi = (Short) f.get(device);
            if (rssi != null && rssi < 0 && rssi >= -100) {
                handleRssi(device, (int) rssi);
                return;
            }
        } catch (Exception ignored) {}

        // 方案3: 调用 readRemoteRssi() 触发异步读取
        try {
            Method m = BluetoothDevice.class.getMethod("readRemoteRssi");
            m.invoke(device);
        } catch (Exception ignored) {}
    }

    private void handleRssi(BluetoothDevice device, int rssi) {
        String mac = device.getAddress();
        Integer old = rssiCache.put(mac, rssi);
        rssiTimeCache.put(mac, System.currentTimeMillis());
        if (old != null && old == rssi) return;

        String name = nameCache.getOrDefault(mac, "");
        Log.d(TAG, "RSSI: " + mac + " = " + rssi + " dBm");
        LogManager.d(TAG, "RSSI: " + mac + " = " + rssi);

        if (engine != null) engine.evaluate(mac, name, rssi);
    }

    // ==================== 状态文件 ====================

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
            ch.setDescription("蓝牙触发器后台运行状态");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
