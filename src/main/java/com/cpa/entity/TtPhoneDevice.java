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
@TableName("tt_phone_device")
@Entity
@Table(name = "tt_phone_device")
public class TtPhoneDevice {

    @TableId(value = "id", type = IdType.AUTO)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("server_ip")
    @Column(name = "server_ip", nullable = false, length = 50)
    private String serverIp;

    @TableField("phone_id")
    @Column(name = "phone_id", nullable = false, unique = true, length = 100)
    private String phoneId;

    @TableField("device_status")
    @Column(name = "device_status", nullable = false, length = 20)
    private String deviceStatus;

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
