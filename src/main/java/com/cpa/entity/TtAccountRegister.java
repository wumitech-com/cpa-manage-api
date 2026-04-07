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
     * ResetPhone 返回的州/省（如 New York）
     */
    @TableField("state")
    @Column(name = "state")
    private String state;

    /**
     * ResetPhone 返回的城市（如 Poughkeepsie）
     */
    @TableField("city")
    @Column(name = "city")
    private String city;

    /**
     * ResetPhone 返回的设备型号（如 SC-51A）
     */
    @TableField("model")
    @Column(name = "model")
    private String model;

    /**
     * ResetPhone 返回的构建号（如 TP1A.220624.014）
     */
    @TableField("build_id")
    @Column(name = "build_id")
    private String buildId;

    /**
     * ResetPhone 返回的 UA
     */
    @TableField("user_agent")
    @Column(name = "user_agent")
    private String userAgent;

    /**
     * ResetPhone 返回的品牌（如 samsung）
     */
    @TableField("brand")
    @Column(name = "brand")
    private String brand;

    /**
     * 国家代码（如 US、BR）
     */
    @TableField("country")
    @Column(name = "country")
    private String country;

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
     * 换机使用的镜像路径（image_path）
     */
    @TableField("image_path")
    @Column(name = "image_path")
    private String imagePath;

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
     * 可能的值：1=true, 0=false, 2=DELAYED（刻意延迟/后续再做）
     */
    @TableField("is_2fa_setup_success")
    @Column(name = "is_2fa_setup_success")
    private Integer is2faSetupSuccess;

    /**
     * 备份接口调用是否成功（backup_success）
     */
    @TableField("backup_success")
    @Column(name = "backup_success")
    private Boolean backupSuccess;

    /**
     * 是否需要后续留存（need_retention）
     * 1=留存完成且后续按留存口径统计（可配合 is_2fa_setup_success=1）
     * 2=留存账号登出（脚本 exitCode=8 后更新）
     * 0=其他/未进入留存
     */
    @TableField("need_retention")
    @Column(name = "need_retention")
    private Integer needRetention;

    /**
     * 封禁时间（block_time），null 表示未封禁
     */
    @TableField("block_time")
    @Column(name = "block_time")
    private LocalDateTime blockTime;

    /**
     * 是否已卖出（is_sell_out），null 表示未卖出
     */
    @TableField("is_sell_out")
    @Column(name = "is_sell_out")
    private Integer isSellOut;

    /**
     * 是否养号（nurture_status：0不养号 1养号）
     */
    @TableField("nurture_status")
    @Column(name = "nurture_status")
    private Integer nurtureStatus;

    /**
     * 是否开窗（shop_status：0未开窗 1已开窗）
     */
    @TableField("shop_status")
    @Column(name = "shop_status")
    private Integer shopStatus;

    /**
     * 新邮箱换绑是否成功（new_email_bind_success）
     * 1=成功, 0=失败, null=未执行/未知
     */
    @TableField("new_email_bind_success")
    @Column(name = "new_email_bind_success")
    private Integer newEmailBindSuccess;

    /**
     * 换绑邮箱（new_email）
     */
    @TableField("new_email")
    @Column(name = "new_email")
    private String newEmail;

    /**
     * 账号备注（note）
     */
    @TableField("note")
    @Column(name = "note")
    private String note;
}

