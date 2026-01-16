package com.cpa.entity;

import lombok.Data;

/**
 * 设备信息
 */
@Data
public class DeviceInfo {
    
    /**
     * 云手机ID
     */
    private String phoneId;
    
    /**
     * 云手机服务器IP
     */
    private String serverIp;
    
    /**
     * 包名
     */
    private String pkgName;
    
    /**
     * 状态
     */
    private Integer status;
    
    /**
     * 上传状态
     */
    private Integer uploadStatus;
}

