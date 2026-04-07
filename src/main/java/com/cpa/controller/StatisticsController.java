package com.cpa.controller;

import com.cpa.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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

    /**
     * 获取每日注册脚本统计（今日成功数、成功率、最近7天趋势）
     * @param date 可选，查询基准日 yyyy-MM-dd，不传则默认今天
     */
    @GetMapping("/daily-register")
    public Map<String, Object> getDailyRegisterStatistics(@RequestParam(required = false) String date) {
        log.info("获取每日注册统计, date={}", date);
        LocalDate queryDate = parseDate(date);
        return statisticsService.getDailyRegisterStatistics(queryDate);
    }

    @GetMapping("/daily-register/overview")
    public Map<String, Object> getDailyRegisterOverview(@RequestParam(required = false) String date) {
        log.info("获取每日注册概览, date={}", date);
        return statisticsService.getDailyRegisterOverview(parseDate(date));
    }

    @GetMapping("/daily-register/trend")
    public Map<String, Object> getDailyRegisterTrend(@RequestParam(required = false) String date) {
        log.info("获取每日注册趋势, date={}", date);
        return statisticsService.getDailyRegisterTrend(parseDate(date));
    }

    @GetMapping("/daily-register/detail")
    public Map<String, Object> getDailyRegisterDetail(@RequestParam(required = false) String date) {
        log.info("获取每日注册详情, date={}", date);
        return statisticsService.getDailyRegisterDetail(parseDate(date));
    }

    /**
     * 获取封号率统计（次日、3天、7天）
     * @param date 可选，查询基准日 yyyy-MM-dd，不传则默认今天
     */
    @GetMapping("/block-rate")
    public Map<String, Object> getBlockRateStatistics(@RequestParam(required = false) String date) {
        log.info("获取封号率统计, date={}", date);
        LocalDate queryDate = parseDate(date);
        return statisticsService.getBlockRateStatistics(queryDate);
    }

    /**
     * 获取封号率最近7天趋势（与上面的卡片无强关联）
     * @param date 可选，作为“今天”的参考 yyyy-MM-dd，不传则默认今天
     */
    @GetMapping("/block-rate-trend")
    public Map<String, Object> getBlockRateTrend(@RequestParam(required = false) String date) {
        log.info("获取封号率趋势统计, date={}", date);
        LocalDate queryDate = parseDate(date);
        return statisticsService.getBlockRateTrend(queryDate);
    }

    /**
     * 获取封号率每日数据（用于回溯矩阵）
     * @param date 可选，查询基准日 yyyy-MM-dd，不传则默认今天
     * @param days 需要返回的天数（包含基准日，向前回溯）
     */
    @GetMapping("/block-rate-daily")
    public Map<String, Object> getBlockRateDaily(@RequestParam(required = false) String date,
                                                   @RequestParam(required = false, defaultValue = "40") int days) {
        log.info("获取封号率每日数据, date={}, days={}", date, days);
        LocalDate queryDate = parseDate(date);
        return statisticsService.getBlockRateDaily(queryDate, days);
    }

    /**
     * 留存日记：按日期查看留存任务执行记录（分页）
     */
    @GetMapping("/retention-records")
    public Map<String, Object> getRetentionRecords(@RequestParam(required = false) String date,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        log.info("获取留存日记记录, date={}, page={}, size={}", date, page, size);
        LocalDate queryDate = parseDate(date);
        if (page < 1) {
            page = 1;
        }
        if (size <= 0) {
            size = 20;
        }
        return statisticsService.getRetentionRecords(queryDate, page, size);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            log.warn("解析日期失败: {}", date);
            return null;
        }
    }
}
