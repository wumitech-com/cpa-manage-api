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
@TableName("tt_task_execution_log")
@Entity
@Table(name = "tt_task_execution_log")
public class TtTaskExecutionLog {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("instance_id")
    @Column(name = "instance_id", nullable = false, length = 100)
    private String instanceId;

    @TableField("log_level")
    @Column(name = "log_level", nullable = false, length = 20)
    private String logLevel;

    @TableField("step_name")
    @Column(name = "step_name", length = 100)
    private String stepName;

    @TableField("message")
    @Column(name = "message", length = 2000)
    private String message;

    @TableField("details_json")
    @Column(name = "details_json")
    private String detailsJson;

    @TableField("created_at")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
