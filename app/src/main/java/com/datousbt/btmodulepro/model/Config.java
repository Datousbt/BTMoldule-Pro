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

    /** 主动轮询间隔 (毫秒)，仅在 ACTIVE / MIXED 模式下生效，默认 5000ms */
    public int pollInterval = 5000;

    /** 触发规则列表 */
    public List<TriggerRule> rules = new ArrayList<>();
}
