package com.datousbt.btmodulepro.hook;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainHook
        extends XposedModule {

    private static final String TAG = "RssiTrigger";

    // --- RSSI 模式常量 (与 Config.rssiMode 对应) ---
    private static final int MODE_PASSIVE = 0;
    private static final int MODE_ACTIVE  = 1;
    private static final int MODE_MIXED   = 2;

    // ============================================================
    // 目标类名
    // ============================================================

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

    // ============================================================
    // 状态
    // ============================================================

    private static final Map<String, Integer> rssiCache = new ConcurrentHashMap<>();
    private static volatile RssiTriggerEngine engine;
    private static volatile HandlerThread pollThread;
    private static volatile Handler pollHandler;
    private static final AtomicBoolean pollRunning = new AtomicBoolean(false);

    // ============================================================
    // LSPosed 生命周期
    // ============================================================

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader cl = param.getDefaultClassLoader();

        if ("android".equals(pkg)) {
            Log.i(TAG, "=== system_server loaded ===");
            loadConfig();
            hookSystemServer(cl);

        } else if ("com.android.bluetooth".equals(pkg)) {
            Log.i(TAG, "=== bluetooth app loaded ===");
            hookBluetoothApp(cl);

            // 根据配置的 RSSI 模式决定是否启动主动轮询
            int mode = (engine != null && engine.config != null)
                    ? engine.config.rssiMode : MODE_MIXED;
            int interval = (engine != null && engine.config != null)
                    ? engine.config.pollInterval : 5000;

            if (mode == MODE_ACTIVE || mode == MODE_MIXED) {
                startPolling(cl, interval);
            } else {
                Log.i(TAG, "RSSI mode=PASSIVE, skipping active polling");
            }
        }
    }

    // ============================================================
    // system_server hook
    // ============================================================

    private void hookSystemServer(ClassLoader cl) {
        for (String cn : SYSTEM_SERVER_TARGETS) {
            int n = tryHookClass(cl, cn);
            if (n > 0) break;
        }
    }

    // ============================================================
    // 蓝牙进程 hook (被动监听)
    // ============================================================

    private void hookBluetoothApp(ClassLoader cl) {
        for (String cn : BLUETOOTH_APP_TARGETS) {
            int n = tryHookClass(cl, cn);
            if (n > 0) break;
        }
    }

    // ============================================================
    // 主动 RSSI 轮询
    // ============================================================

    private void startPolling(ClassLoader cl, int interval) {
        if (pollRunning.getAndSet(true)) return;

        if (interval <= 0) {
            Log.i(TAG, "pollInterval=0, using default 5000ms");
            interval = 5000;
        }

        pollThread = new HandlerThread("RssiPoll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());

        final int finalInterval = interval;
        Log.i(TAG, "Active polling started, interval=" + finalInterval + "ms");

        pollHandler.post(new Runnable() {
            @Override
            public void run() {
                pollRssiForConnectedDevices();
                if (pollHandler != null) {
                    pollHandler.postDelayed(this, finalInterval);
                }
            }
        });
    }

    private void pollRssiForConnectedDevices() {
        try {
            Object adapter = Class.forName("android.bluetooth.BluetoothAdapter")
                    .getMethod("getDefaultAdapter").invoke(null);
            if (adapter == null) return;

            java.util.List<?> devices = (java.util.List<?>) adapter.getClass()
                    .getMethod("getConnectedDevices").invoke(adapter);
            if (devices == null) return;

            for (Object dev : devices) {
                if (dev instanceof BluetoothDevice) {
                    BluetoothDevice device = (BluetoothDevice) dev;
                    try {
                        Method readRssi = BluetoothDevice.class.getMethod("readRemoteRssi");
                        readRssi.invoke(device);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "poll error: " + t.getMessage());
        }
    }

    // ============================================================
    // 类级别 hook
    // ============================================================

    private int tryHookClass(ClassLoader cl, String className) {
        try {
            Class<?> target = cl.loadClass(className);
            Log.i(TAG, "Found: " + target.getName());
            int count = hookRssiMethods(target);
            count += hookHandlerIfPresent(target);
            return count;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "Not found: " + className);
            return 0;
        } catch (Throwable t) {
            Log.e(TAG, "Error: " + className + " " + t.getMessage());
            return 0;
        }
    }

    private int hookRssiMethods(Class<?> target) {
        int count = 0;
        for (Method method : target.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (matchesRssiSignature(types) || isRssiMethodName(method.getName())) {
                hook(method).intercept(this::onRssiCallback);
                count++;
            }
        }
        return count;
    }

    private boolean matchesRssiSignature(Class<?>[] types) {
        boolean hasDevice = false, hasValue = false;
        for (Class<?> t : types) {
            if (t == BluetoothDevice.class || t == String.class) hasDevice = true;
            if (t == int.class || t == Integer.class || t == short.class || t == Short.class) hasValue = true;
        }
        return hasDevice && hasValue && types.length <= 3;
    }

    private boolean isRssiMethodName(String name) {
        String lower = name.toLowerCase();
        for (String n : RSSI_METHOD_NAMES) {
            if (lower.contains(n.toLowerCase())) return true;
        }
        return false;
    }

    private int hookHandlerIfPresent(Class<?> target) {
        int count = 0;
        for (Class<?> inner : target.getDeclaredClasses()) {
            if (android.os.Handler.class.isAssignableFrom(inner)) {
                try {
                    hook(inner.getMethod("handleMessage", Message.class))
                            .intercept(this::onHandlerMessage);
                    count++;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return count;
    }

    // ============================================================
    // 回调
    // ============================================================

    private Object onHandlerMessage(Chain chain) throws Throwable {
        try {
            Message msg = (Message) chain.getArgs().get(0);
            if (msg != null && msg.obj instanceof BluetoothDevice) {
                int rssi = msg.arg1;
                if (rssi < 0 && rssi >= -100) {
                    processRssi((BluetoothDevice) msg.obj, rssi);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Handler error", t);
        }
        return chain.proceed();
    }

    private Object onRssiCallback(Chain chain) throws Throwable {
        try {
            Object[] args = chain.getArgs().toArray();
            BluetoothDevice device = null;
            int rssi = Integer.MIN_VALUE;
            for (Object arg : args) {
                if (arg instanceof BluetoothDevice) device = (BluetoothDevice) arg;
                else if (arg instanceof Integer || arg instanceof Short) {
                    int val = ((Number) arg).intValue();
                    if (val < 0 && val >= -100) rssi = val;
                }
            }
            if (device != null && rssi != Integer.MIN_VALUE) {
                processRssi(device, rssi);
            }
        } catch (Throwable t) {
            Log.e(TAG, "RSSI callback error", t);
        }
        return chain.proceed();
    }

    // ============================================================
    // RSSI 处理
    // ============================================================

    private void processRssi(BluetoothDevice device, int rssi) {
        try {
            String mac = device.getAddress();
            String name = device.getName();
            Integer old = rssiCache.put(mac, rssi);
            if (old != null && old == rssi) return; // 没变，跳过

            Log.d(TAG, "RSSI: " + (name != null ? name : mac) + " = " + rssi + " dBm");
            if (engine != null) engine.evaluate(mac, name != null ? name : "", rssi);
        } catch (Throwable t) {
            Log.e(TAG, "processRssi error", t);
        }
    }

    private static void loadConfig() {
        try {
            engine = RssiTriggerEngine.loadFromPath(
                    "/data/data/com.datousbt.btmodulepro/files/rssi_config.json");
            Log.i(TAG, "Config loaded: " +
                    (engine.config.rules.size()) + " rules, mode=" + engine.config.rssiMode);
        } catch (Throwable t) {
            Log.e(TAG, "loadConfig error", t);
            engine = new RssiTriggerEngine();
        }
    }
}
