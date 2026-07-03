package com.datousbt.btmodulepro.model;

import java.util.ArrayList;
import java.util.List;

public class Config {

    /** 全局启用开关 */
    public boolean enabled = true;

    /**
     * RSSI 信号获取模式:
     * 0 = 仅被动监听 (PASSIVE)
     * 1 = 仅主动轮询 (ACTIVE)
     * 2 = 被动监听 + 主动轮询 (MIXED，默认)
     */
    public int rssiMode = 2;

    /** 主动轮询间隔 (毫秒)，默认 5000ms */
    public int pollInterval = 5000;

    // --- 全局 RSSI 阈值 (所有规则共用) ---

    /** 接近阈值 (dBm)，默认 -60 */
    public int aboveThreshold = -60;

    /** 远离阈值 (dBm)，默认 -80 */
    public int belowThreshold = -80;

    /** 回滞 (dBm)，默认 5 */
    public int hysteresis = 5;

    /** 防抖间隔 (毫秒)，默认 5000 */
    public int debounce = 5000;

    // --- 日志设置 ---

    /** 日志开关 */
    public boolean logEnabled = false;

    /** 日志存放路径 */
    public String logPath = "/data/data/com.datousbt.btmodulepro/files/btmodulepro.log";

    /** 日志文件大小限制 (KB)，默认 1024KB = 1MB */
    public int logMaxSizeKb = 1024;

    // --- 规则列表 ---

    public List<TriggerRule> rules = new ArrayList<>();
}
