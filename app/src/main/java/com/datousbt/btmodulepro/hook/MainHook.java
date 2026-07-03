package com.datousbt.btmodulepro.hook;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import com.datousbt.btmodulepro.util.LogManager;

import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainHook extends XposedModule {

    private static final String TAG = "MainHook";
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
    private static String configPath = "/data/data/com.datousbt.btmodulepro/files/rssi_config.json";
    private static String statusPath = "/data/data/com.datousbt.btmodulepro/files/rssi_status.json";

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader cl = param.getDefaultClassLoader();

        if ("android".equals(pkg)) {
            loadConfig();
            hookSystemServer(cl);
        } else if ("com.android.bluetooth".equals(pkg)) {
            hookBluetoothApp(cl);
            int mode = engine != null ? engine.config.rssiMode : MODE_MIXED;
            int interval = engine != null ? engine.config.pollInterval : 5000;
            if (mode == MODE_ACTIVE || mode == MODE_MIXED) startPolling(cl, interval);
            else LogManager.i(TAG, "被动监听模式，跳过主动轮询");
        }
    }

    // --- hook 安装 ---

    private void hookSystemServer(ClassLoader cl) {
        for (String cn : SYSTEM_SERVER_TARGETS) {
            if (tryHookClass(cl, cn) > 0) break;
        }
        LogManager.i(TAG, "system_server hooks done, targets: " + String.join(", ", SYSTEM_SERVER_TARGETS));
    }

    private void hookBluetoothApp(ClassLoader cl) {
        for (String cn : BLUETOOTH_APP_TARGETS) {
            if (tryHookClass(cl, cn) > 0) break;
        }
        LogManager.i(TAG, "bluetooth app hooks done, targets: " + String.join(", ", BLUETOOTH_APP_TARGETS));
    }

    private int tryHookClass(ClassLoader cl, String className) {
        try {
            Class<?> target = cl.loadClass(className);
            LogManager.i(TAG, "Hook found: " + target.getName());
            int count = hookRssiMethods(target);
            count += hookHandlerIfPresent(target);
            return count;
        } catch (ClassNotFoundException e) {
            LogManager.d(TAG, "Hook not found: " + className);
            return 0;
        } catch (Throwable t) {
            LogManager.e(TAG, "Hook error: " + className, t);
            return 0;
        }
    }

    private int hookRssiMethods(Class<?> target) {
        int count = 0;
        for (Method m : target.getDeclaredMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (matchesRssiSig(types) || isRssiName(m.getName())) {
                hook(m).intercept(this::onRssi);
                count++;
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
            if (android.os.Handler.class.isAssignableFrom(inner)) {
                try {
                    hook(inner.getMethod("handleMessage", Message.class)).intercept(this::onHandlerMsg);
                    c++;
                    LogManager.i(TAG, "  Handler hook: " + inner.getSimpleName());
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return c;
    }

    // --- 主动轮询 ---

    private void startPolling(ClassLoader cl, int interval) {
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

    private Object onHandlerMsg(Chain chain) throws Throwable {
        try {
            Message msg = (Message) chain.getArgs().get(0);
            if (msg != null && msg.obj instanceof BluetoothDevice) {
                int r = msg.arg1;
                if (r < 0 && r >= -100) processRssi((BluetoothDevice) msg.obj, r);
            }
        } catch (Throwable t) { LogManager.e(TAG, "Handler error", t); }
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
            if (dev != null && rssi != Integer.MIN_VALUE) processRssi(dev, rssi);
        } catch (Throwable t) { LogManager.e(TAG, "RSSI callback error", t); }
        return chain.proceed();
    }

    // --- RSSI 处理 ---

    private void processRssi(BluetoothDevice device, int rssi) {
        try {
            String mac = device.getAddress();
            String name = device.getName();
            if (name != null) nameCache.put(mac, name);

            Integer old = rssiCache.put(mac, rssi);
            rssiTimeCache.put(mac, System.currentTimeMillis());
            if (old != null && old == rssi) return;

            LogManager.d(TAG, "RSSI: " + (name != null ? name : mac) + " = " + rssi + " dBm");

            // RSSI 状态写入文件供 UI 读取
            writeRssiStatus();

            if (engine != null) engine.evaluate(mac, name != null ? name : "", rssi);
        } catch (Throwable t) { LogManager.e(TAG, "processRssi error", t); }
    }

    // --- RSSI 状态文件 (供 UI 实时读取) ---

    private void writeRssiStatus() {
        try {
            StringBuilder sb = new StringBuilder("{\n  \"devices\": {\n");
            boolean first = true;
            for (Map.Entry<String, Integer> e : rssiCache.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                String mac = e.getKey();
                String name = nameCache.getOrDefault(mac, "");
                long time = rssiTimeCache.getOrDefault(mac, 0L);
                sb.append("    \"").append(mac).append("\": {");
                sb.append("\"name\":\"").append(escapeJson(name)).append("\",");
                sb.append("\"rssi\":").append(e.getValue()).append(",");
                sb.append("\"time\":").append(time);
                sb.append("}");
            }
            sb.append("\n  }\n}");
            FileWriter fw = new FileWriter(statusPath);
            fw.write(sb.toString());
            fw.flush();
            fw.close();
        } catch (Throwable ignored) {}
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --- 配置 ---

    private static void loadConfig() {
        try {
            engine = RssiTriggerEngine.loadFromPath(configPath);
        } catch (Throwable t) {
            LogManager.e(TAG, "loadConfig error", t);
            engine = new RssiTriggerEngine();
        }
    }
}
