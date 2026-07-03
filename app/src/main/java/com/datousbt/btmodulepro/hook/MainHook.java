package com.datousbt.btmodulepro.hook;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import de.robv.android.xposed.IXposedMod;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import com.datousbt.btmodulepro.util.LogManager;

import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainHook extends XposedModule implements IXposedMod {

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
    private static final String configPath = "/data/data/com.datousbt.btmodulepro/files/rssi_config.json";
    private static final String statusPath = "/data/data/com.datousbt.btmodulepro/files/rssi_status.json";

    private static volatile long lastConfigMod = 0;
    private static volatile HandlerThread watcherThread;
    private static final AtomicBoolean watcherRunning = new AtomicBoolean(false);

    // ==================== LSPosed 生命周期 ====================

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader cl = param.getDefaultClassLoader();

        if ("android".equals(pkg)) {
            Log.i(TAG, "=== system_server ===");
            loadConfig();
            hookPackage(cl, SYSTEM_SERVER_TARGETS, "system_server");
            startConfigWatcher();

        } else if ("com.android.bluetooth".equals(pkg)) {
            Log.i(TAG, "=== bluetooth app ===");
            hookPackage(cl, BLUETOOTH_APP_TARGETS, "bluetooth");
            startConfigWatcher();

            int mode = engine != null ? engine.config.rssiMode : MODE_MIXED;
            int interval = engine != null ? engine.config.pollInterval : 5000;
            if (mode == MODE_ACTIVE || mode == MODE_MIXED) {
                startPolling(interval);
            } else {
                Log.i(TAG, "Mode=PASSIVE, no polling");
            }
        }
    }

    // ==================== 配置热加载 ====================

    private void startConfigWatcher() {
        if (watcherRunning.getAndSet(true)) return;
        watcherThread = new HandlerThread("CfgWatcher");
        watcherThread.start();
        Handler wh = new Handler(watcherThread.getLooper());
        Log.i(TAG, "Config watcher started (3s)");
        wh.post(new Runnable() {
            public void run() {
                try {
                    java.io.File f = new java.io.File(configPath);
                    if (f.exists()) {
                        long mod = f.lastModified();
                        if (mod > lastConfigMod) {
                            lastConfigMod = mod;
                            Log.i(TAG, "Config changed, reloading");
                            loadConfig();
                        }
                    }
                } catch (Throwable ignored) {}
                wh.postDelayed(this, 3000);
            }
        });
    }

    // ==================== Hook 安装 ====================

    private void hookPackage(ClassLoader cl, String[] targets, String where) {
        for (String cn : targets) {
            if (tryHookClass(cl, cn, where)) break;
        }
        Log.i(TAG, where + " hook done");
    }

    private boolean tryHookClass(ClassLoader cl, String className, String where) {
        try {
            Class<?> target = cl.loadClass(className);
            Log.i(TAG, "[" + where + "] FOUND: " + target.getName());
            int n = hookMethods(target, where);
            n += hookHandlerIfPresent(target, where);
            Log.i(TAG, "[" + where + "] " + n + " hooks in " + className);
            return n > 0;
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "[" + where + "] NOT FOUND: " + className);
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "[" + where + "] ERROR " + className + ": " + t.getMessage());
            return false;
        }
    }

    private int hookMethods(Class<?> target, String where) {
        int count = 0;
        for (Method m : target.getDeclaredMethods()) {
            if (matchesRssiSig(m.getParameterTypes()) || isRssiName(m.getName())) {
                try {
                    hook(m).intercept(this::onRssi);
                    count++;
                } catch (Throwable ignored) {}
            }
        }
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

    private int hookHandlerIfPresent(Class<?> target, String where) {
        int c = 0;
        for (Class<?> inner : target.getDeclaredClasses()) {
            if (Handler.class.isAssignableFrom(inner)) {
                try {
                    hook(inner.getMethod("handleMessage", Message.class))
                            .intercept(this::onHandlerMsg);
                    c++;
                    Log.i(TAG, "[" + where + "] Handler: " + inner.getSimpleName());
                } catch (Throwable ignored) {}
            }
        }
        return c;
    }

    // ==================== 主动轮询 ====================

    private void startPolling(int interval) {
        if (pollRunning.getAndSet(true)) return;
        if (interval <= 0) interval = 5000;
        pollThread = new HandlerThread("RssiPoll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        final int fi = interval;
        Log.i(TAG, "Active poll: " + fi + "ms");
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
            if (devices == null || devices.isEmpty()) return;
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

    // ==================== 回调 ====================

    private Object onHandlerMsg(Chain chain) throws Throwable {
        try {
            Message msg = (Message) chain.getArgs().get(0);
            if (msg != null && msg.obj instanceof BluetoothDevice) {
                int r = msg.arg1;
                if (r < 0 && r >= -100) {
                    processRssi((BluetoothDevice) msg.obj, r);
                }
            }
        } catch (Throwable t) { LogManager.e(TAG, "Handler err", t); }
        return chain.proceed();
    }

    private Object onRssi(Chain chain) throws Throwable {
        try {
            Object[] args = chain.getArgs().toArray();
            BluetoothDevice dev = null;
            int rssi = Integer.MIN_VALUE;
            for (Object a : args) {
                if (a instanceof BluetoothDevice) dev = (BluetoothDevice) a;
                else if (a instanceof Integer || a instanceof Short) {
                    int v = ((Number) a).intValue();
                    if (v < 0 && v >= -100) rssi = v;
                }
            }
            if (dev != null && rssi != Integer.MIN_VALUE) {
                processRssi(dev, rssi);
            }
        } catch (Throwable t) { LogManager.e(TAG, "RSSI cb err", t); }
        return chain.proceed();
    }

    // ==================== RSSI 处理 ====================

    private void processRssi(BluetoothDevice device, int rssi) {
        try {
            String mac = device.getAddress();
            String name = device.getName();
            if (name != null) nameCache.put(mac, name);
            Integer old = rssiCache.put(mac, rssi);
            rssiTimeCache.put(mac, System.currentTimeMillis());
            if (old != null && old == rssi) return;
            LogManager.d(TAG, "RSSI: " + (name != null ? name : mac) + " = " + rssi);
            writeRssiStatus();
            if (engine != null) engine.evaluate(mac, name != null ? name : "", rssi);
        } catch (Throwable t) { LogManager.e(TAG, "processRssi err", t); }
    }

    private void writeRssiStatus() {
        try {
            StringBuilder sb = new StringBuilder("{\"devices\":{");
            boolean first = true;
            for (Map.Entry<String, Integer> e : rssiCache.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                String mac = e.getKey();
                String nm = nameCache.getOrDefault(mac, "");
                long tm = rssiTimeCache.getOrDefault(mac, 0L);
                sb.append("\"").append(mac).append("\":{");
                sb.append("\"name\":\"").append(esc(nm)).append("\",");
                sb.append("\"rssi\":").append(e.getValue()).append(",");
                sb.append("\"time\":").append(tm).append("}");
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
            Log.i(TAG, "Config: mode=" + engine.config.rssiMode +
                    " log=" + engine.config.logEnabled +
                    " rules=" + engine.config.rules.size());
        } catch (Throwable t) {
            Log.e(TAG, "loadConfig err", t);
            engine = new RssiTriggerEngine();
        }
    }
}
