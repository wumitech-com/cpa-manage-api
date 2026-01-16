package com.cpa.controller;

import com.cpa.service.TaskSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 任务管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskSchedulerService taskSchedulerService;

    /**
     * 手动触发每日任务
     */
    @PostMapping("/daily")
    public Map<String, Object> manualTriggerDailyTask() {
        log.info("接收到手动触发每日任务请求");
        return taskSchedulerService.manualTriggerDailyTask();
    }

    /**
     * 获取任务调度状态
     */
    @GetMapping("/schedule")
    public Map<String, Object> getTaskScheduleStatus() {
        log.info("获取任务调度状态");
        return taskSchedulerService.getTaskScheduleStatus();
    }

    /**
     * 获取定时任务配置信息
     */
    @GetMapping("/config")
    public Map<String, Object> getTaskConfig() {
        Map<String, Object> response = Map.of(
            "success", true,
            "data", Map.of(
                "dailyTaskCron", "0 0 8 * * ?",
                "dailyTaskDescription", "每天8点执行",
                "hourlyTaskCron", "0 0 * * * ?",
                "hourlyTaskDescription", "每小时执行检查",
                "taskTypes", Map.of(
                    "register", "批量注册任务",
                    "nurture", "批量养号任务",
                    "business", "批量业务任务"
                )
            )
        );
        
        return response;
    }
}
