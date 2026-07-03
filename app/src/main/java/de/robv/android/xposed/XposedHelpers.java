package de.robv.android.xposed;

import java.lang.reflect.Method;

/** Stub — 运行时由 LSPosed/XposedBridge 提供真正实现 */
public class XposedHelpers {

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return classLoader.loadClass(className);
    }

    public static void findAndHookMethod(Class<?> clazz, String methodName,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub only — LSPosed provides real implementation");
    }

    public static void findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("Stub only — LSPosed provides real implementation");
    }
}
