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
@TableName("tt_email_pool")
@Entity
@Table(name = "tt_email_pool")
public class TtEmailPool {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("email")
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @TableField("password")
    @Column(name = "password", nullable = false, length = 255)
    private String password;

    @TableField("client_id")
    @Column(name = "client_id", length = 128)
    private String clientId;

    @TableField("refresh_token")
    @Column(name = "refresh_token")
    private String refreshToken;

    @TableField("channel")
    @Column(name = "channel", length = 64)
    private String channel;

    @TableField("usage_status")
    @Column(name = "usage_status", nullable = false, length = 20)
    private String usageStatus;

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
