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
@TableName("tt_task_template")
@Entity
@Table(name = "tt_task_template")
public class TtTaskTemplate {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("template_code")
    @Column(name = "template_code", nullable = false, unique = true, length = 100)
    private String templateCode;

    @TableField("name")
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @TableField("app_code")
    @Column(name = "app_code", nullable = false, length = 50)
    private String appCode;

    @TableField("task_type")
    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @TableField("executor_type")
    @Column(name = "executor_type", nullable = false, length = 100)
    private String executorType;

    @TableField("status")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @TableField("config_json")
    @Column(name = "config_json")
    private String configJson;

    @TableField("created_at")
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @TableField("updated_at")
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
