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
@TableName("tt_task_batch")
@Entity
@Table(name = "tt_task_batch")
public class TtTaskBatch {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("batch_id")
    @Column(name = "batch_id", nullable = false, unique = true, length = 100)
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

    @TableField("idempotency_key")
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @TableField("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @TableField("total_count")
    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    @TableField("success_count")
    @Column(name = "success_count", nullable = false)
    private Integer successCount;

    @TableField("fail_count")
    @Column(name = "fail_count", nullable = false)
    private Integer failCount;

    @TableField("submitted_by")
    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @TableField("created_at")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
