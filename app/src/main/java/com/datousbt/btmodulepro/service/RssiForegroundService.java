package com.datousbt.btmodulepro.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前台服务 — 蓝牙 RSSI 监听 + 命令触发。
 * 不依赖 LSPosed hook，在 APP 进程内通过标准 Android API 完成。
 */
public class RssiForegroundService extends Service {

    private static final String TAG = "RssiService";
    private static final String CHANNEL_ID = "rssi_service";
    private static final int NOTIFY_ID = 1;

    private BluetoothAdapter btAdapter;
    private RssiTriggerEngine engine;
    private Config config;
    private HandlerThread workerThread;
    private Handler worker;
    private boolean running = false;

    private final Map<String, Integer> rssiCache = new ConcurrentHashMap<>();
    private final Map<String, Long> rssiTimeCache = new ConcurrentHashMap<>();

    private final Runnable pollRunnable = new Runnable() {
        public void run() {
            pollRssi();
            if (running && worker != null) {
                worker.postDelayed(this, 5000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service onStartCommand");
        if (!running) start();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    // --- 启动/停止 ---

    private void start() {
        running = true;
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        config = ConfigManager.load(this);
        engine = new RssiTriggerEngine(config);
        LogManager.configure(config.logEnabled, config.logPath, config.logMaxSizeKb);
        LogManager.i(TAG, "Service started, rules=" + config.rules.size());

        workerThread = new HandlerThread("RssiWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        // 前台通知
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("蓝牙触发器运行中")
                .setContentText("正在监听蓝牙信号强度")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setOngoing(true)
                .build();
        startForeground(NOTIFY_ID, notification);

        // 注册蓝牙广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(btReceiver, filter);

        // 启动轮询
        worker.post(pollRunnable);
        Log.i(TAG, "Service fully started");
    }

    private void stop() {
        running = false;
        if (worker != null) worker.removeCallbacks(pollRunnable);
        if (workerThread != null) workerThread.quitSafely();
        try { unregisterReceiver(btReceiver); } catch (Exception ignored) {}
        stopForeground(true);
        LogManager.i(TAG, "Service stopped");
    }

    // --- 蓝牙广播 ---

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if (device == null) return;

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                Log.i(TAG, "ACL connected: " + device.getAddress());
                // 立即读一次 RSSI
                readRssiNow(device);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                Log.i(TAG, "ACL disconnected: " + device.getAddress());
                rssiCache.remove(device.getAddress());
                rssiTimeCache.remove(device.getAddress());
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1);
                if (state == BluetoothAdapter.STATE_CONNECTED) {
                    Log.i(TAG, "BT connected: " + device.getAddress());
                    readRssiNow(device);
                }
            }
        }
    };

    // --- RSSI 轮询 ---

    private void pollRssi() {
        if (!running || btAdapter == null || !btAdapter.isEnabled()) return;

        // 热加载配置
        config = ConfigManager.load(this);
        LogManager.configure(config.logEnabled, config.logPath, config.logMaxSizeKb);
        engine.config = config;

        // 获取所有已连接设备
        try {
            Set<BluetoothDevice> devices = btAdapter.getBondedDevices();
            if (devices == null) return;
            for (BluetoothDevice device : devices) {
                readRssiNow(device);
            }
        } catch (SecurityException ignored) {}
    }

    private void readRssiNow(BluetoothDevice device) {
        try {
            // 先检查这个设备是否是我们在意的
            boolean interested = false;
            for (TriggerRule rule : config.rules) {
                if (rule.enable && device.getAddress().equalsIgnoreCase(rule.mac)) {
                    interested = true;
                    break;
                }
            }
            if (!interested) return;

            // 用反射调 readRemoteRssi（隐藏API）
            java.lang.reflect.Method m = BluetoothDevice.class.getMethod("readRemoteRssi");
            m.invoke(device);

            // 同时尝试读取缓存的 RSSI
            // getRssi() 在某些 Android 版本上是隐藏的
            try {
                java.lang.reflect.Method getRssi = BluetoothDevice.class.getMethod("getRssi");
                Integer rssi = (Integer) getRssi.invoke(device);
                if (rssi != null) {
                    onRssiUpdate(device, rssi);
                }
            } catch (NoSuchMethodException ignored) {}

        } catch (Exception ignored) {}
    }

    private void onRssiUpdate(BluetoothDevice device, int rssi) {
        if (rssi >= 0 || rssi < -100) return;

        String mac = device.getAddress();
        String name = device.getName();

        Integer old = rssiCache.put(mac, rssi);
        rssiTimeCache.put(mac, System.currentTimeMillis());
        if (old != null && old == rssi) return;

        Log.d(TAG, "RSSI: " + mac + " = " + rssi + " dBm");

        // 写状态文件（UI 读取）
        writeStatus();

        // 触发规则
        engine.evaluate(mac, name != null ? name : "", rssi);
    }

    private void writeStatus() {
        try {
            StringBuilder sb = new StringBuilder("{\"devices\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : rssiCache.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(e.getKey()).append("\":{");
                sb.append("\"name\":\"\",");
                sb.append("\"rssi\":").append(e.getValue()).append(",");
                sb.append("\"time\":").append(rssiTimeCache.getOrDefault(e.getKey(), 0L)).append("}");
            }
            sb.append("}}");
            FileWriter fw = new FileWriter(
                    new java.io.File(getFilesDir(), "rssi_status.json"));
            fw.write(sb.toString());
            fw.flush();
            fw.close();
        } catch (Exception ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "蓝牙信号监听", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("蓝牙触发器运行状态");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}
