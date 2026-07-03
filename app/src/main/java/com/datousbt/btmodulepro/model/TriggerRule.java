package com.datousbt.btmodulepro.model;

public class TriggerRule {

    /** 设备显示名称 */
    public String name;

    /** 蓝牙设备 MAC 地址 (必填) */
    public String mac;

    /** 蓝牙名称过滤 (可选，为空则仅按 MAC 匹配) */
    public String deviceName;

    /** 信号强时执行的 shell 命令 (RSSI &gt; aboveThreshold，为空则不执行) */
    public String aboveCommand;

    /** 信号弱时执行的 shell 命令 (RSSI &lt; belowThreshold，为空则不执行) */
    public String belowCommand;

    /** 是否启用 */
    public boolean enable;

    public TriggerRule() {
        enable = true;
    }
}
