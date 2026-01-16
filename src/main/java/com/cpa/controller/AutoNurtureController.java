package com.cpa.controller;

import com.cpa.config.AutoNurtureConfig;
import com.cpa.service.AutoNurtureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 自动养号控制器
 */
@RestController
@RequestMapping("/api/auto-nurture")
@Slf4j
public class AutoNurtureController {
    
    @Autowired
    private AutoNurtureService autoNurtureService;
    
    /**
     * 启动自动养号任务
     */
    @PostMapping("/start")
    public Map<String, Object> startAutoNurture(@RequestBody AutoNurtureConfig config) {
        log.info("接收到启动自动养号任务请求: {}", config);
        return autoNurtureService.startAutoNurture(config);
    }
    
    /**
     * 查询任务状态
     */
    @GetMapping("/status/{taskId}")
    public Map<String, Object> getTaskStatus(@PathVariable String taskId) {
        return autoNurtureService.getTaskStatus(taskId);
    }
    
    /**
     * 获取任务列表
     */
    @GetMapping("/tasks")
    public Map<String, Object> getTaskList() {
        return autoNurtureService.getTaskList();
    }
}

