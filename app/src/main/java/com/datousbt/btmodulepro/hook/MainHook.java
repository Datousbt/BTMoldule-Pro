package com.datousbt.btmodulepro.hook;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.datousbt.btmodulepro.util.LogManager;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "RssiTrigger";
    private static final int MODE_PASSIVE = 0, MODE_ACTIVE = 1, MODE_MIXED = 2;

    private static final String[] SYSTEM_SERVER_TARGETS = {
            "com.android.server.bluetooth.BluetoothManagerService",
            "com.android.server.bluetooth.BluetoothManagerService$BluetoothHandler",
    };

    private static final String[] BLUETOOTH_APP_TARGETS = {
            "com.android.bluetooth.btservice.AdapterService",
            "com.android.bluetooth.btservice.RemoteDevices",
            "com.google.android.bluetooth.btservice.AdapterService",
            "com.xiaomi.bluetooth.btservice.AdapterService",
            "miui.bluetooth.btservice.MiuiAdapterService",
    };

    private static final String[] RSSI_METHOD_NAMES = {
            "onReadRemoteRssi", "onRemoteRssi", "handleReadRemoteRssi",
            "readRemoteRssi", "onRssiUpdate", "updateRssi", "setRssi",
            "devicePropertyChanged", "onDevicePropertyChanged",
            "onRemoteDeviceProperties", "onRemoteDevicePropertiesUpdate",
    };

    private static final Map<String, Integer> rssiCache = new ConcurrentHashMap<>();
    private static final Map<String, String> nameCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> rssiTimeCache = new ConcurrentHashMap<>();
    private static volatile RssiTriggerEngine engine;

    private static volatile HandlerThread pollThread;
    private static volatile Handler pollHandler;
    private static final AtomicBoolean pollRunning = new AtomicBoolean(false);
    private static long lastConfigModified = 0;
    private static final String configPath = "/data/data/com.datousbt.btmodulepro/files/rssi_config.json";
    private static final String statusPath = "/data/data/com.datousbt.btmodulepro/files/rssi_status.json";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;

        if ("android".equals(pkg)) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "=== system_server loaded, pkg=" + pkg + " ===");
            Log.i(TAG, "========================================");
            loadConfig();
            hookPackage(lpparam.classLoader, SYSTEM_SERVER_TARGETS);

        } else if ("com.android.bluetooth".equals(pkg)) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "=== bluetooth app loaded, pkg=" + pkg + " ===");
            Log.i(TAG, "========================================");
            hookPackage(lpparam.classLoader, BLUETOOTH_APP_TARGETS);

            int mode = engine != null ? engine.config.rssiMode : MODE_MIXED;
            int interval = engine != null ? engine.config.pollInterval : 5000;
            if (mode == MODE_ACTIVE || mode == MODE_MIXED) {
                startPolling(interval);
            } else {
                LogManager.i(TAG, "被动监听模式，跳过主动轮询");
            }
        }
    }

    // --- hook 安装 ---

    private void hookPackage(ClassLoader cl, String[] targets) {
        for (String className : targets) {
            if (tryHookClass(cl, className)) break;
        }
        LogManager.i(TAG, "Hooks done");
    }

    private boolean tryHookClass(ClassLoader cl, String className) {
        try {
            Class<?> target = XposedHelpers.findClass(className, cl);
            LogManager.i(TAG, "Hook found: " + target.getName());
            return hookMethods(target) + hookHandlerIfPresent(target) > 0;
        } catch (ClassNotFoundException e) {
            LogManager.d(TAG, "Hook not found: " + className);
            return false;
        } catch (Throwable t) {
            LogManager.e(TAG, "Hook error: " + className, t);
            return false;
        }
    }

    private int hookMethods(Class<?> target) {
        int count = 0;
        for (Method m : target.getDeclaredMethods()) {
            if (matchesRssiSig(m.getParameterTypes()) || isRssiName(m.getName())) {
                try {
                    XposedHelpers.findAndHookMethod(target, m.getName(), m.getParameterTypes(),
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    onRssi(param);
                                }
                            });
                    count++;
                } catch (Throwable ignored) {}
            }
        }
        LogManager.d(TAG, "  " + target.getSimpleName() + ": " + count + " method hooks");
        return count;
    }

    private boolean matchesRssiSig(Class<?>[] types) {
        boolean d = false, v = false;
        for (Class<?> t : types) {
            if (t == BluetoothDevice.class || t == String.class) d = true;
            if (t == int.class || t == Integer.class || t == short.class || t == Short.class) v = true;
        }
        return d && v && types.length <= 3;
    }

    private boolean isRssiName(String n) {
        String l = n.toLowerCase();
        for (String s : RSSI_METHOD_NAMES) if (l.contains(s.toLowerCase())) return true;
        return false;
    }

    private int hookHandlerIfPresent(Class<?> target) {
        int c = 0;
        for (Class<?> inner : target.getDeclaredClasses()) {
            if (Handler.class.isAssignableFrom(inner)) {
                try {
                    XposedHelpers.findAndHookMethod(inner, "handleMessage", Message.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    onHandlerMsg(param);
                                }
                            });
                    c++;
                    LogManager.i(TAG, "  Handler hook: " + inner.getSimpleName());
                } catch (Throwable ignored) {}
            }
        }
        return c;
    }

    // --- 主动轮询 ---

    private void startPolling(int interval) {
        if (pollRunning.getAndSet(true)) return;
        if (interval <= 0) interval = 5000;
        pollThread = new HandlerThread("RssiPoll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        final int fi = interval;
        LogManager.i(TAG, "主动轮询启动，间隔=" + fi + "ms");
        pollHandler.post(new Runnable() {
            public void run() {
                pollRssi();
                if (pollHandler != null) pollHandler.postDelayed(this, fi);
            }
        });
    }

    private void pollRssi() {
        try {
            Object adapter = Class.forName("android.bluetooth.BluetoothAdapter")
                    .getMethod("getDefaultAdapter").invoke(null);
            if (adapter == null) return;
            java.util.List<?> devices = (java.util.List<?>) adapter.getClass()
                    .getMethod("getConnectedDevices").invoke(adapter);
            if (devices == null) return;
            for (Object d : devices) {
                if (d instanceof BluetoothDevice) {
                    try {
                        Method m = BluetoothDevice.class.getMethod("readRemoteRssi");
                        m.invoke(d);
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    // --- 回调 ---

    private void onHandlerMsg(XC_MethodHook.MethodHookParam param) {
        try {
            Message msg = (Message) param.args[0];
            if (msg != null && msg.obj instanceof BluetoothDevice) {
                int r = msg.arg1;
                if (r < 0 && r >= -100) processRssi((BluetoothDevice) msg.obj, r);
            }
        } catch (Throwable t) { LogManager.e(TAG, "Handler error", t); }
    }

    private void onRssi(XC_MethodHook.MethodHookParam param) {
        try {
            BluetoothDevice dev = null;
            int rssi = Integer.MIN_VALUE;
            for (Object a : param.args) {
                if (a instanceof BluetoothDevice) dev = (BluetoothDevice) a;
                else if (a instanceof Integer || a instanceof Short) {
                    int v = ((Number) a).intValue();
                    if (v < 0 && v >= -100) rssi = v;
                }
            }
            if (dev != null && rssi != Integer.MIN_VALUE) processRssi(dev, rssi);
        } catch (Throwable t) { LogManager.e(TAG, "RSSI callback error", t); }
    }

    // --- RSSI 处理 ---

    private void processRssi(BluetoothDevice device, int rssi) {
        try {
            // 热加载：检查配置文件是否被修改，是则重新加载
            checkConfigReload();

            String mac = device.getAddress();
            String name = device.getName();
            if (name != null) nameCache.put(mac, name);

            Integer old = rssiCache.put(mac, rssi);
            rssiTimeCache.put(mac, System.currentTimeMillis());
            if (old != null && old == rssi) return;

            LogManager.d(TAG, "RSSI: " + (name != null ? name : mac) + " = " + rssi + " dBm");
            writeRssiStatus();

            if (engine != null) engine.evaluate(mac, name != null ? name : "", rssi);
        } catch (Throwable t) { LogManager.e(TAG, "processRssi error", t); }
    }

    // 每 5 秒最多检查一次配置文件修改
    private static long lastConfigCheck = 0;
    private void checkConfigReload() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck < 5000) return;
        lastConfigCheck = now;
        try {
            java.io.File f = new java.io.File(configPath);
            if (f.exists() && f.lastModified() > lastConfigModified) {
                lastConfigModified = f.lastModified();
                Log.i(TAG, "Config modified, reloading...");
                loadConfig();
            }
        } catch (Throwable ignored) {}
    }

    private void writeRssiStatus() {
        try {
            StringBuilder sb = new StringBuilder("{\"devices\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : rssiCache.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                String mac = e.getKey();
                String name = nameCache.getOrDefault(mac, "");
                long time = rssiTimeCache.getOrDefault(mac, 0L);
                sb.append("\"").append(mac).append("\":{");
                sb.append("\"name\":\"").append(esc(name)).append("\",");
                sb.append("\"rssi\":").append(e.getValue()).append(",");
                sb.append("\"time\":").append(time).append("}");
            }
            sb.append("}}");
            FileWriter fw = new FileWriter(statusPath);
            fw.write(sb.toString());
            fw.flush();
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void loadConfig() {
        try {
            engine = RssiTriggerEngine.loadFromPath(configPath);
            LogManager.i(TAG, "Config loaded: mode=" + engine.config.rssiMode +
                    ", log=" + engine.config.logEnabled +
                    ", rules=" + engine.config.rules.size());
        } catch (Throwable t) {
            Log.e(TAG, "RssiTrigger loadConfig error", t);
            LogManager.e(TAG, "loadConfig error", t);
            engine = new RssiTriggerEngine();
        }
    }
}
