package de.robv.android.xposed.callbacks;

/** Stub — 运行时由 LSPosed/XposedBridge 提供真正实现 */
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public Object appInfo;
        public boolean isFirstApplication;
    }
}
