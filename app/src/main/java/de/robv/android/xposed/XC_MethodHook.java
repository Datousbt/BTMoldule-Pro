package de.robv.android.xposed;

/** Stub — 运行时由 LSPosed/XposedBridge 提供真正实现 */
public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
