package com.cpa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tt_phone_server")
@Entity
@Table(name = "tt_phone_server")
public class TtPhoneServer {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("server_ip")
    @Column(name = "server_ip", nullable = false, length = 50, unique = true)
    private String serverIp;

    @TableField("xray_server_ip")
    @Column(name = "xray_server_ip", length = 50)
    private String xrayServerIp;

    @TableField("appium_server")
    @Column(name = "appium_server", length = 100)
    private String appiumServer;

    @TableField("max_concurrency")
    @Column(name = "max_concurrency", nullable = false)
    private Integer maxConcurrency;

    /**
     * 0启用 1禁用
     */
    @TableField("status")
    @Column(name = "status", nullable = false)
    private Integer status;

    @TableField("usage_scope")
    @Column(name = "usage_scope", nullable = false, length = 20)
    private String usageScope;

    @TableField("note")
    @Column(name = "note", length = 500)
    private String note;

    @TableField("created_at")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
