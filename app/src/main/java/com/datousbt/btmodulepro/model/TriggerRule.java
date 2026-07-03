package com.datousbt.btmodulepro.model;

public class TriggerRule {

    /** 设备显示名称 */
    public String name;

    /** 蓝牙设备 MAC 地址 (必填) */
    public String mac;

    /** 蓝牙名称过滤 (可选，为空则仅按 MAC 匹配) */
    public String deviceName;

    /**
     * 接近阈值 (dBm)，例如 -60。
     * 当 RSSI &gt; aboveThreshold 时，认为信号强（靠近设备），触发 aboveCommand。
     * 设为 0 表示不启用接近检测。
     */
    public int aboveThreshold;

    /**
     * 远离阈值 (dBm)，例如 -80。
     * 当 RSSI &lt; belowThreshold 时，认为信号弱（远离设备），触发 belowCommand。
     * 设为 0 表示不启用远离检测。
     */
    public int belowThreshold;

    /** 信号强时执行的 shell 命令 (RSSI &gt; aboveThreshold，为空则不执行) */
    public String aboveCommand;

    /** 信号弱时执行的 shell 命令 (RSSI &lt; belowThreshold，为空则不执行) */
    public String belowCommand;

    /** 是否启用 */
    public boolean enable;

    /** 防抖间隔 (毫秒)，同一设备两次触发的最小间隔 */
    public int debounce;

    /**
     * 回滞 (dBm)，避免 RSSI 在阈值边界来回抖动。
     * 例如 aboveThreshold=-60, hysteresis=5：
     *   触发进入"信号强"需要 RSSI &gt; -55 (= -60+5)
     *   退出"信号强"需要 RSSI &lt; -65 (= -60-5)
     * 例如 belowThreshold=-80, hysteresis=5：
     *   触发进入"信号弱"需要 RSSI &lt; -85 (= -80-5)
     *   退出"信号弱"需要 RSSI &gt; -75 (= -80+5)
     */
    public int hysteresis;

    public TriggerRule() {
        enable = true;
        aboveThreshold = -60;
        belowThreshold = -80;
        debounce = 5000;
        hysteresis = 5;
    }
}
