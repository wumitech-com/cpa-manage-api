package com.cpa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tt_register_dispatch_log")
public class TtRegisterDispatchLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("batch_id")
    private String batchId;

    @TableField("task_id")
    private String taskId;

    @TableField("register_task_id")
    private Long registerTaskId;

    @TableField("server_ip")
    private String serverIp;

    @TableField("phone_id")
    private String phoneId;

    @TableField("old_status")
    private String oldStatus;

    @TableField("new_status")
    private String newStatus;

    @TableField("task_type")
    private String taskType;

    @TableField("target_count")
    private Integer targetCount;

    @TableField("is_continuous")
    private Integer isContinuous;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("result")
    private String result;

    @TableField("message")
    private String message;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
