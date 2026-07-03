package com.datousbt.btmodulepro.hook;

import android.util.Log;

import com.datousbt.btmodulepro.model.Config;
import com.datousbt.btmodulepro.model.TriggerRule;
import com.datousbt.btmodulepro.shell.RootExecutor;
import com.datousbt.btmodulepro.storage.ConfigManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RSSI 触发引擎 — 双阈值独立状态机。
 *
 * <pre>
 * 状态机 (每条规则独立维护)：
 *
 *   进入 ABOVE：RSSI &gt; aboveThreshold + hysteresis → 触发 aboveCommand → 进入 ABOVE
 *   离开 ABOVE：RSSI &lt; aboveThreshold - hysteresis → 回到 IDLE
 *
 *   进入 BELOW：RSSI &lt; belowThreshold - hysteresis → 触发 belowCommand → 进入 BELOW
 *   离开 BELOW：RSSI &gt; belowThreshold + hysteresis → 回到 IDLE
 *
 *   在滞回区间内：状态保持不变，命令只触发一次
 * </pre>
 *
 * 防循环保证：
 * 1. 命令仅在状态变化时触发一次（IDLE→ABOVE 或 IDLE→BELOW）
 * 2. 状态保持期间不重复触发
 * 3. 滞回区间防止阈值边界抖动
 * 4. debounce 计时器提供额外的防抖保护
 */
public class RssiTriggerEngine {

    private static final String TAG = "RssiTriggerEngine";

    private enum State {
        /** 中间区域 — 信号在 above 和 below 两个阈值之间 */
        IDLE,
        /** 信号强 — 已触发 aboveCommand */
        ABOVE,
        /** 信号弱 — 已触发 belowCommand */
        BELOW
    }

    final Config config;

    /** rule index → 当前状态 */
    private final Map<Integer, State> stateMap = new ConcurrentHashMap<>();

    /** rule index → 上次触发时间 */
    private final Map<Integer, Long> lastTriggerMap = new ConcurrentHashMap<>();

    public RssiTriggerEngine() {
        this.config = new Config();
    }

    public RssiTriggerEngine(Config config) {
        this.config = config;
    }

    public static RssiTriggerEngine loadFromPath(String path) {
        Config cfg = ConfigManager.loadFromPath(path);
        return new RssiTriggerEngine(cfg);
    }

    /**
     * 每次收到 RSSI 更新时调用。
     *
     * @param mac        设备 MAC 地址
     * @param deviceName 设备名称
     * @param rssi       当前信号值 (dBm)，范围通常 -100 ~ -20
     */
    public void evaluate(String mac, String deviceName, int rssi) {
        if (!config.enabled) return;

        for (int i = 0; i < config.rules.size(); i++) {
            TriggerRule rule = config.rules.get(i);
            if (!rule.enable) continue;

            // MAC 匹配 (大小写不敏感)
            if (!mac.equalsIgnoreCase(rule.mac)) continue;

            // 名称过滤
            if (rule.deviceName != null && !rule.deviceName.isEmpty()) {
                if (!deviceName.contains(rule.deviceName)) continue;
            }

            evaluateRule(i, rule, rssi);
        }
    }

    private void evaluateRule(int index, TriggerRule rule, int rssi) {
        State currentState = stateMap.getOrDefault(index, State.IDLE);
        State newState = currentState;

        boolean aboveEnabled = rule.aboveThreshold < 0 && rule.aboveCommand != null && !rule.aboveCommand.isEmpty();
        boolean belowEnabled = rule.belowThreshold < 0 && rule.belowCommand != null && !rule.belowCommand.isEmpty();

        int hyst = rule.hysteresis;

        if (currentState == State.ABOVE) {
            // 当前信号强：检查是否应该退出
            if (rssi < rule.aboveThreshold - hyst) {
                newState = State.IDLE;
                Log.i(TAG, "[" + rule.name + "] 离开信号强区域 (RSSI=" + rssi +
                        " < " + (rule.aboveThreshold - hyst) + ")");
            }
        } else if (currentState == State.BELOW) {
            // 当前信号弱：检查是否应该退出
            if (rssi > rule.belowThreshold + hyst) {
                newState = State.IDLE;
                Log.i(TAG, "[" + rule.name + "] 离开信号弱区域 (RSSI=" + rssi +
                        " > " + (rule.belowThreshold + hyst) + ")");
            }
        } else {
            // 当前在 IDLE：检查是否满足触发条件
            if (aboveEnabled && rssi > rule.aboveThreshold + hyst) {
                newState = State.ABOVE;
                Log.i(TAG, "[" + rule.name + "] 进入信号强区域 (RSSI=" + rssi +
                        " > " + (rule.aboveThreshold + hyst) + ")");
            } else if (belowEnabled && rssi < rule.belowThreshold - hyst) {
                newState = State.BELOW;
                Log.i(TAG, "[" + rule.name + "] 进入信号弱区域 (RSSI=" + rssi +
                        " < " + (rule.belowThreshold - hyst) + ")");
            }
            // 在中间区域 → 保持 IDLE
        }

        // 状态变化 → 执行命令
        if (newState != currentState) {
            stateMap.put(index, newState);

            // 防抖检查
            long now = System.currentTimeMillis();
            Long lastTrigger = lastTriggerMap.get(index);
            if (lastTrigger != null && (now - lastTrigger) < rule.debounce) {
                Log.d(TAG, "  [" + rule.name + "] 防抖拦截 (距上次触发 " + (now - lastTrigger) + "ms)");
                return;
            }
            lastTriggerMap.put(index, now);

            // 仅在进入 ABOVE/BELOW 时执行对应命令
            if (newState == State.ABOVE) {
                Log.i(TAG, "  >>> [" + rule.name + "] 执行信号强命令: " + rule.aboveCommand);
                RootExecutor.exec(rule.aboveCommand);

            } else if (newState == State.BELOW) {
                Log.i(TAG, "  >>> [" + rule.name + "] 执行信号弱命令: " + rule.belowCommand);
                RootExecutor.exec(rule.belowCommand);

            }
            // IDLE ← 仅做日志记录，不执行命令
        }
    }
}
