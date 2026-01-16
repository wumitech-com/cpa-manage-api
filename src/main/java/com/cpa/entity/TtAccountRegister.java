package com.cpa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * TikTok账号注册信息表
 */
@Data
@TableName("tt_account_register")
@Entity
@Table(name = "tt_account_register")
public class TtAccountRegister {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 云手机ID
     */
    @TableField("phone_id")
    @Column(name = "phone_id", nullable = false)
    private String phoneId;

    /**
     * 云手机服务器IP
     */
    @TableField("phone_server_ip")
    @Column(name = "phone_server_ip")
    private String phoneServerIp;

    /**
     * 账号（邮箱）
     */
    @TableField("email")
    @Column(name = "email")
    private String email;

    /**
     * 密码
     */
    @TableField("password")
    @Column(name = "password")
    private String password;

    /**
     * TikTok用户名
     */
    @TableField("username")
    @Column(name = "username")
    private String username;

    /**
     * 养号json来源
     */
    @TableField("nurture_json_source")
    @Column(name = "nurture_json_source")
    private String nurtureJsonSource;

    /**
     * Google Advertising ID
     */
    @TableField("gaid")
    @Column(name = "gaid")
    private String gaid;

    /**
     * 安卓版本
     */
    @TableField("android_version")
    @Column(name = "android_version")
    private String androidVersion;

    /**
     * IP渠道
     */
    @TableField("ip_channel")
    @Column(name = "ip_channel")
    private String ipChannel;

    /**
     * IP地址
     */
    @TableField("ip")
    @Column(name = "ip")
    private String ip;

    /**
     * 行为（nickname_behavior_result）
     */
    @TableField("behavior")
    @Column(name = "behavior")
    private String behavior;

    /**
     * TikTok版本
     */
    @TableField("tiktok_version")
    @Column(name = "tiktok_version")
    private String tiktokVersion;

    /**
     * 流量数据
     */
    @TableField("trafficData")
    @Column(name = "trafficData")
    private String trafficData;

    /**
     * 认证密钥（authenticator_key）
     */
    @TableField("authenticator_key")
    @Column(name = "authenticator_key")
    private String authenticatorKey;

    /**
     * 注册脚本开始执行时间
     */
    @TableField("script_start_time")
    @Column(name = "script_start_time")
    private LocalDateTime scriptStartTime;

    /**
     * 注册时间
     */
    @TableField("register_time")
    @Column(name = "register_time")
    private LocalDateTime registerTime;

    /**
     * 记录创建时间
     */
    @TableField("created_at")
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 记录更新时间
     */
    @TableField("updated_at")
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 是否注册成功
     */
    @TableField("register_success")
    @Column(name = "register_success")
    private Boolean registerSuccess;

    /**
     * 注册类型：FAKE_EMAIL-假邮箱注册，REAL_EMAIL-真邮箱注册（Outlook）
     */
    @TableField("register_type")
    @Column(name = "register_type")
    private String registerType;

    /**
     * Outlook信息（outlook_info）
     */
    @TableField("outlook_info")
    @Column(name = "outlook_info")
    private String outlookInfo;

    /**
     * 2FA是否设置成功（is_2fa_setup_success）
     */
    @TableField("is_2fa_setup_success")
    @Column(name = "is_2fa_setup_success")
    private Boolean is2faSetupSuccess;

    /**
     * 备份接口调用是否成功（backup_success）
     */
    @TableField("backup_success")
    @Column(name = "backup_success")
    private Boolean backupSuccess;
}

