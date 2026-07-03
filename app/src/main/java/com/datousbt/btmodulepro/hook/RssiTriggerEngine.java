package com.datousbt.btmodulepro.hook;

import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.model.TriggerRule;
import com.datousbt.btmodulepro.shell.RootExecutor;
import com.datousbt.btmodulepro.storage.ConfigManager;
import com.datousbt.btmodulepro.util.LogManager;

import java.io.File;
import java.io.FileWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSSI 触发引擎 — 全局双阈值 + 状态机 + 防循环。
 *
 * <pre>
 * 状态机 (每条规则独立维护)：
 *
 *   进入 ABOVE：RSSI &gt; aboveThreshold + hysteresis → 触发 aboveCommand → 进入 ABOVE
 *   离开 ABOVE：RSSI &lt; aboveThreshold - hysteresis → 回到 IDLE
 *
 *   进入 BELOW：RSSI &lt; belowThreshold - hysteresis → 触发 belowCommand → 进入 BELOW
 *   离开 BELOW：RSSI &gt; belowThreshold + hysteresis → 回到 IDLE
 * </pre>
 */
public class RssiTriggerEngine {

    private static final String TAG = "TriggerEngine";

    private enum State { IDLE, ABOVE, BELOW }

    final Config config;

    private final Map<Integer, State> stateMap = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastTriggerMap = new ConcurrentHashMap<>();

    public RssiTriggerEngine() { this.config = new Config(); }

    public RssiTriggerEngine(Config config) { this.config = config; }

    public static RssiTriggerEngine loadFromPath(String path) {
        Config cfg = ConfigManager.loadFromPath(path);
        RssiTriggerEngine engine = new RssiTriggerEngine(cfg);
        // 配置日志
        LogManager.configure(cfg.logEnabled, cfg.logPath, cfg.logMaxSizeKb);
        LogManager.i(TAG, "Engine loaded: " + cfg.rules.size() + " rules, mode=" + cfg.rssiMode);
        return engine;
    }

    /**
     * 每次收到 RSSI 更新时调用。
     */
    public void evaluate(String mac, String deviceName, int rssi) {
        if (!config.enabled) return;

        for (int i = 0; i < config.rules.size(); i++) {
            TriggerRule rule = config.rules.get(i);
            if (!rule.enable) continue;
            if (!mac.equalsIgnoreCase(rule.mac)) continue;
            if (rule.deviceName != null && !rule.deviceName.isEmpty()
                    && !deviceName.contains(rule.deviceName)) continue;

            evaluateRule(i, rule, rssi);
        }
    }

    private void evaluateRule(int index, TriggerRule rule, int rssi) {
        State currentState = stateMap.getOrDefault(index, State.IDLE);
        State newState = currentState;

        int above = config.aboveThreshold;
        int below = config.belowThreshold;
        int hyst = config.hysteresis;

        boolean aboveOk = above < 0 && rule.aboveCommand != null && !rule.aboveCommand.isEmpty();
        boolean belowOk = below < 0 && rule.belowCommand != null && !rule.belowCommand.isEmpty();

        if (currentState == State.ABOVE) {
            if (rssi < above - hyst) {
                newState = State.IDLE;
                LogManager.i(TAG, "[" + rule.name + "] 离开信号强: rssi=" + rssi + " < " + (above - hyst));
            }
        } else if (currentState == State.BELOW) {
            if (rssi > below + hyst) {
                newState = State.IDLE;
                LogManager.i(TAG, "[" + rule.name + "] 离开信号弱: rssi=" + rssi + " > " + (below + hyst));
            }
        } else {
            if (aboveOk && rssi > above + hyst) {
                newState = State.ABOVE;
                LogManager.i(TAG, "[" + rule.name + "] 进入信号强: rssi=" + rssi + " > " + (above + hyst));
            } else if (belowOk && rssi < below - hyst) {
                newState = State.BELOW;
                LogManager.i(TAG, "[" + rule.name + "] 进入信号弱: rssi=" + rssi + " < " + (below - hyst));
            }
        }

        if (newState != currentState) {
            stateMap.put(index, newState);

            long now = System.currentTimeMillis();
            Long last = lastTriggerMap.get(index);
            if (last != null && (now - last) < config.debounce) {
                LogManager.d(TAG, "[" + rule.name + "] 防抖跳过 (" + (now - last) + "ms)");
                return;
            }
            lastTriggerMap.put(index, now);

            if (newState == State.ABOVE) {
                LogManager.i(TAG, ">>> [" + rule.name + "] 执行接近命令: " + rule.aboveCommand);
                RootExecutor.exec(rule.aboveCommand);
            } else if (newState == State.BELOW) {
                LogManager.i(TAG, ">>> [" + rule.name + "] 执行远离命令: " + rule.belowCommand);
                RootExecutor.exec(rule.belowCommand);
            }
        }
    }
}
