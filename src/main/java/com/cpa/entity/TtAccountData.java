package com.cpa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 已注册账号库表（主表）
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("tt_account_data")
@Entity
@Table(name = "tt_account_data")
public class TtAccountData {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 云手机ID
     */
    @TableField("phone_id")
    private String phoneId;

    /**
     * 云手机服务器ID
     */
    @TableField("phone_server_id")
    private String phoneServerId;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * URL
     */
    @TableField("url")
    private String url;

    /**
     * TT用户名
     */
    @TableField("tt_user_name")
    private String ttUserName;

    /**
     * TT密码
     */
    @TableField("tt_password")
    private String ttPassword;

    /**
     * TT简介
     */
    @TableField("tt_bio")
    private String ttBio;

    /**
     * 邮箱账号
     */
    @TableField("email_account")
    private String emailAccount;

    /**
     * 编辑状态 0: 未编辑 1: 已经编辑过
     */
    @TableField("edit_status")
    private Integer editStatus;

    /**
     * 邮箱状态 0: 未绑定邮箱 1: 已绑定邮箱
     */
    @TableField("email_status")
    private Integer emailStatus;

    /**
     * 状态 0: 正常 1: 封号 2: 冷却
     */
    @TableField("status")
    private Integer status;

    /**
     * 注释说明
     */
    @TableField("note")
    private String note;

    /**
     * 邮箱密码
     */
    @TableField("email_password")
    private String emailPassword;

    /**
     * 上传状态 0: 不上传视频 1: 上传视频
     */
    @TableField("upload_status")
    private Integer uploadStatus;

    /**
     * 已关注名称
     */
    @TableField("following_name")
    private String followingName;

    /**
     * 国家
     */
    @TableField("country")
    private String country;

    /**
     * offer的app包名
     */
    @TableField("pkg_name")
    private String pkgName;

    /**
     * 邮箱昵称
     */
    @TableField("email_fullname")
    private String emailFullname;

    /**
     * 刷视频天数
     */
    @TableField("video_days")
    private Integer videoDays;

    /**
     * 养号状态 0: 养号中 1: 养号完成
     */
    @TableField("nurture_status")
    private Integer nurtureStatus;
}
