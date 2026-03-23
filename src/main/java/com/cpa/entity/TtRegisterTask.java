package com.cpa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 注册任务表
 */
@Data
@TableName("tt_register_task")
@Entity
@Table(name = "tt_register_task")
public class TtRegisterTask {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 任务ID（唯一标识）
     */
    @TableField("task_id")
    @Column(name = "task_id", unique = true, nullable = false, length = 100)
    private String taskId;

    /**
     * 任务类型：FAKE_EMAIL-假邮箱注册, REAL_EMAIL-真邮箱注册（Outlook）
     */
    @TableField("task_type")
    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    /**
     * 任务种类：REGISTER-注册任务, RETENTION-留存任务
     * 默认为 REGISTER
     */
    @TableField("task_kind")
    @Column(name = "task_kind", length = 20)
    private String taskKind;

    /**
     * 设备类型：CLOUD_PHONE-云手机, MAINBOARD-主板机
     * 默认为 CLOUD_PHONE（云手机），用于区分不同的设备平台
     */
    @TableField("device_type")
    @Column(name = "device_type", length = 20)
    private String deviceType;

    /**
     * 服务器IP
     */
    @TableField("server_ip")
    @Column(name = "server_ip", nullable = false, length = 50)
    private String serverIp;

    /**
     * 云手机ID（单个设备）
     */
    @TableField("phone_id")
    @Column(name = "phone_id", nullable = false, length = 100)
    private String phoneId;

    /**
     * 目标注册数量：1-注册1个账号, >1-注册多个账号, 0-无限循环
     */
    @TableField("target_count")
    @Column(name = "target_count")
    private Integer targetCount;

    /**
     * TikTok版本目录
     */
    @TableField("tiktok_version_dir")
    @Column(name = "tiktok_version_dir", length = 200)
    private String tiktokVersionDir;

    /**
     * 国家代码
     */
    @TableField("country")
    @Column(name = "country", length = 10)
    private String country;

    /**
     * SDK版本
     */
    @TableField("sdk")
    @Column(name = "sdk", length = 10)
    private String sdk;

    /**
     * 镜像路径
     */
    @TableField("image_path")
    @Column(name = "image_path", length = 500)
    private String imagePath;

    /**
     * GAID标签
     */
    @TableField("gaid_tag")
    @Column(name = "gaid_tag", length = 50)
    private String gaidTag;

    /**
     * 动态IP渠道
     */
    @TableField("dynamic_ip_channel")
    @Column(name = "dynamic_ip_channel", length = 50)
    private String dynamicIpChannel;

    /**
     * 静态IP渠道
     */
    @TableField("static_ip_channel")
    @Column(name = "static_ip_channel", length = 50)
    private String staticIpChannel;

    /**
     * 业务标识
     */
    @TableField("biz")
    @Column(name = "biz", length = 100)
    private String biz;

    /**
     * ADB端口（主板机使用）
     */
    @TableField("adb_port")
    @Column(name = "adb_port", length = 10)
    private String adbPort;

    /**
     * Appium服务器地址（用于执行注册脚本）
     */
    @TableField("appium_server")
    @Column(name = "appium_server", length = 50)
    private String appiumServer;

    /**
     * 任务状态：PENDING-待执行, RUNNING-运行中, COMPLETED-已完成, FAILED-失败, STOPPED-已停止
     */
    @TableField("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * 记录创建时间
     */
    @TableField("created_at")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 记录更新时间
     */
    @TableField("updated_at")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

