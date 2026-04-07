package com.cpa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 开窗明细表（tt_follow_details_new）
 */
@Data
@TableName("tt_follow_details_new")
@Entity
@Table(name = "tt_follow_details_new")
public class TtFollowDetailsNew {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("phone_id")
    @Column(name = "phone_id")
    private String phoneId;

    @TableField("phone_server_id")
    @Column(name = "phone_server_id")
    private String phoneServerId;

    @TableField("gaid")
    @Column(name = "gaid")
    private String gaid;

    @TableField("android_version")
    @Column(name = "android_version")
    private String androidVersion;

    @TableField("tiktok_version")
    @Column(name = "tiktok_version")
    private String tiktokVersion;

    @TableField("username")
    @Column(name = "username")
    private String username;

    @TableField("register_time")
    @Column(name = "register_time")
    private LocalDateTime registerTime;

    @TableField("following_account")
    @Column(name = "following_account")
    private String followingAccount;

    @TableField("following_type")
    @Column(name = "following_type")
    private Integer followingType;

    @TableField("created_at")
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @TableField("ip")
    @Column(name = "ip")
    private String ip;

    @TableField("authenticator_key")
    @Column(name = "authenticator_key")
    private String authenticatorKey;

    @TableField("note")
    @Column(name = "note")
    private String note;

    @TableField("password")
    @Column(name = "password")
    private String password;

    @TableField("pkg_name")
    @Column(name = "pkg_name")
    private String pkgName;

    @TableField("upload_status")
    @Column(name = "upload_status")
    private Integer uploadStatus;

    @TableField("shop_status")
    @Column(name = "shop_status")
    private Integer shopStatus;

    @TableField("upload_time")
    @Column(name = "upload_time")
    private LocalDateTime uploadTime;

    @TableField("nurture_status")
    @Column(name = "nurture_status")
    private Integer nurtureStatus;

    /**
     * 养号策略（如：发布+浏览 / 浏览）
     */
    @TableField("nurture_strategy")
    @Column(name = "nurture_strategy")
    private String nurtureStrategy;

    /**
     * 养号设备（如：ARM架构 / 云手机 / 魔云腾 / 其他）
     */
    @TableField("nurture_device")
    @Column(name = "nurture_device")
    private String nurtureDevice;

    /**
     * 国家（如：US / MX / BR / JP）
     */
    @TableField("country")
    @Column(name = "country")
    private String country;

    /**
     * 注册IP归属（展示用）
     */
    @TableField("register_ip_region")
    @Column(name = "register_ip_region")
    private String registerIpRegion;

    /**
     * 注册环境（展示用）
     */
    @TableField("register_env")
    @Column(name = "register_env")
    private String registerEnv;

    /**
     * 刷粉日期（开窗管理）
     */
    @TableField("fan_date")
    @Column(name = "fan_date")
    private LocalDateTime fanDate;

    /**
     * 养号日期（开窗管理）
     */
    @TableField("nurture_date")
    @Column(name = "nurture_date")
    private LocalDateTime nurtureDate;
}
