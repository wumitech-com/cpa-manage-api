package com.cpa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 留存任务执行记录表（不修改注册表，单独记录留存相关信息）
 */
@Data
@TableName("tt_retention_record")
@Entity
@Table(name = "tt_retention_record")
public class TtRetentionRecord {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 留存任务ID（关联 tt_register_task.task_id） */
    @TableField("task_id")
    @Column(name = "task_id", nullable = false, length = 100)
    private String taskId;

    /** 云手机ID */
    @TableField("phone_id")
    @Column(name = "phone_id", nullable = false, length = 100)
    private String phoneId;

    /** 云手机服务器IP */
    @TableField("phone_server_ip")
    @Column(name = "phone_server_ip", length = 50)
    private String phoneServerIp;

    /** 账号注册表ID（关联 tt_account_register.id） */
    @TableField("account_register_id")
    @Column(name = "account_register_id")
    private Long accountRegisterId;

    /** 账号 GAID */
    @TableField("gaid")
    @Column(name = "gaid", length = 100)
    private String gaid;

    /** 留存脚本是否执行成功 */
    @TableField("script_success")
    @Column(name = "script_success")
    private Boolean scriptSuccess;

    /** 备份接口调用是否成功（脚本成功或失败都会调用备份） */
    @TableField("backup_success")
    @Column(name = "backup_success")
    private Boolean backupSuccess;

    /** 记录创建时间（执行时间） */
    @TableField("created_at")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
