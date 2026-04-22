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
@TableName("tt_task_instance")
@Entity
@Table(name = "tt_task_instance")
public class TtTaskInstance {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("instance_id")
    @Column(name = "instance_id", nullable = false, unique = true, length = 100)
    private String instanceId;

    @TableField("batch_id")
    @Column(name = "batch_id", nullable = false, length = 100)
    private String batchId;

    @TableField("template_code")
    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode;

    @TableField("app_code")
    @Column(name = "app_code", nullable = false, length = 50)
    private String appCode;

    @TableField("task_type")
    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @TableField("executor_type")
    @Column(name = "executor_type", nullable = false, length = 100)
    private String executorType;

    @TableField("resource_server_ip")
    @Column(name = "resource_server_ip", length = 50)
    private String resourceServerIp;

    @TableField("resource_phone_id")
    @Column(name = "resource_phone_id", length = 100)
    private String resourcePhoneId;

    @TableField("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @TableField("priority")
    @Column(name = "priority", nullable = false)
    private Integer priority;

    @TableField("payload_json")
    @Column(name = "payload_json")
    private String payloadJson;

    @TableField("error_message")
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @TableField("created_at")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
