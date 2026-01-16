package com.cpa.controller;

import com.cpa.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据统计控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    /**
     * 获取整体统计概览
     */
    @GetMapping("/overview")
    public Map<String, Object> getOverallStatistics() {
        log.info("获取整体统计概览");
        return statisticsService.getOverallStatistics();
    }

    /**
     * 获取设备池统计信息
     */
    @GetMapping("/device-pool")
    public Map<String, Object> getDevicePoolStatistics() {
        log.info("获取设备池统计信息");
        return statisticsService.getDevicePoolStatistics();
    }

    /**
     * 获取账号库统计信息
     */
    @GetMapping("/account-library")
    public Map<String, Object> getAccountLibraryStatistics() {
        log.info("获取账号库统计信息");
        return statisticsService.getAccountLibraryStatistics();
    }

    /**
     * 获取趋势统计（最近7天）
     */
    @GetMapping("/trends")
    public Map<String, Object> getTrendStatistics() {
        log.info("获取趋势统计");
        return statisticsService.getTrendStatistics();
    }

    /**
     * 获取脚本执行统计
     */
    @GetMapping("/script-execution")
    public Map<String, Object> getScriptExecutionStatistics() {
        log.info("获取脚本执行统计");
        return statisticsService.getScriptExecutionStatistics();
    }

    /**
     * 获取设备利用率统计
     */
    @GetMapping("/device-utilization")
    public Map<String, Object> getDeviceUtilizationStatistics() {
        log.info("获取设备利用率统计");
        return statisticsService.getDeviceUtilizationStatistics();
    }
}
