package com.cpa.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cpa.entity.TtRegisterTask;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 注册任务Repository
 */
@Mapper
public interface TtRegisterTaskRepository extends BaseMapper<TtRegisterTask> {
    
    /**
     * 根据任务ID查询任务
     */
    default TtRegisterTask findByTaskId(String taskId) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getTaskId, taskId);
        return selectOne(wrapper);
    }
    
    /**
     * 根据状态查询任务列表
     */
    default List<TtRegisterTask> findByStatus(String status) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getStatus, status);
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }
    
    /**
     * 根据状态列表查询任务列表
     */
    default List<TtRegisterTask> findByStatusIn(List<String> statuses) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(TtRegisterTask::getStatus, statuses);
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }
    
    /**
     * 根据设备ID查询任务列表
     */
    default List<TtRegisterTask> findByPhoneId(String phoneId) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getPhoneId, phoneId);
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }
    
    /**
     * 根据设备ID和状态查询任务列表
     */
    default List<TtRegisterTask> findByPhoneIdAndStatus(String phoneId, String status) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getPhoneId, phoneId);
        wrapper.eq(TtRegisterTask::getStatus, status);
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }
    
    /**
     * 根据服务器IP查询任务列表
     */
    default List<TtRegisterTask> findByServerIp(String serverIp) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getServerIp, serverIp);
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }
    
    /**
     * 根据任务类型查询任务列表
     */
    default List<TtRegisterTask> findByTaskType(String taskType) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getTaskType, taskType);
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }
    
    /**
     * 查询超过指定时间未更新的 RUNNING 状态任务
     * @param status 任务状态
     * @param beforeTime 在此之前未更新的任务
     * @return 任务列表
     */
    default List<TtRegisterTask> findRunningTasksNotUpdatedSince(String status, LocalDateTime beforeTime) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getStatus, status);
        wrapper.lt(TtRegisterTask::getUpdatedAt, beforeTime);
        wrapper.orderByAsc(TtRegisterTask::getUpdatedAt);
        return selectList(wrapper);
    }
    
    /**
     * 根据设备类型和状态查询任务列表（用于主板机养号任务）
     * @param deviceType 设备类型（MAINBOARD-主板机, CLOUD_PHONE-云手机）
     * @param status 任务状态
     * @return 任务列表
     */
    default List<TtRegisterTask> findByDeviceTypeAndStatus(String deviceType, String status) {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getDeviceType, deviceType);
        wrapper.eq(TtRegisterTask::getStatus, status);
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }

    /**
     * 查询待执行的留存任务（task_kind=RETENTION, status=PENDING, device_type 为 CLOUD_PHONE 或 null）
     */
    default List<TtRegisterTask> findPendingRetentionTasks() {
        LambdaQueryWrapper<TtRegisterTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TtRegisterTask::getTaskKind, "RETENTION");
        wrapper.eq(TtRegisterTask::getStatus, "PENDING");
        wrapper.and(w -> w.isNull(TtRegisterTask::getDeviceType).or().eq(TtRegisterTask::getDeviceType, "CLOUD_PHONE"));
        wrapper.orderByDesc(TtRegisterTask::getCreatedAt);
        return selectList(wrapper);
    }
}

