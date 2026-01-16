package com.cpa.config;

import lombok.Data;

import java.util.List;

/**
 * 自动养号任务配置
 */
@Data
public class AutoNurtureConfig {
    
    /**
     * 执行轮次
     */
    private Integer rounds = 3;
    
    /**
     * 每组设备数
     */
    private Integer groupSize = 10;
    
    /**
     * 是否上传视频
     */
    private Boolean uploadVideo = false;
    
    /**
     * 允许的IP地区
     */
    private List<String> allowedCountries = List.of("US", "CA");
    
    /**
     * 设备状态过滤
     * 0: 正常账号
     * 2: 黑名单/冷却期
     */
    private Integer deviceStatus = 0;
    
    /**
     * 上传状态过滤
     * 0: 不需要上传
     * 1: 需要上传
     */
    private Integer uploadStatus = 0;
    
    /**
     * 云手机服务器IP
     */
    private String phoneServerIp = "10.7.107.224";
    
    /**
     * 每组超时时间（分钟）
     */
    private Integer groupTimeout = 40;
}

