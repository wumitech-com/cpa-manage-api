package com.cpa.service;

import com.cpa.repository.TtAccountDataOutlookRepository;
import com.cpa.repository.TtAccountDataRepository;
import com.cpa.repository.TtAccountRegisterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据统计服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final TtAccountDataOutlookRepository outlookRepository;
    private final TtAccountDataRepository accountRepository;
    private final TtAccountRegisterRepository ttAccountRegisterRepository;
    private final com.cpa.repository.TtRetentionRecordRepository ttRetentionRecordRepository;

    /**
     * 获取设备池统计信息
     */
    public Map<String, Object> getDevicePoolStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 总设备数量
            long totalDevices = outlookRepository.selectCount(null);
            
            // 各状态设备数量
            List<Map<String, Object>> statusCounts = outlookRepository.countByStatus();
            
            // 需要注册的设备数量
            List<com.cpa.entity.TtAccountDataOutlook> devicesNeedRegister = outlookRepository.findDevicesNeedRegister();
            int needRegisterCount = devicesNeedRegister.size();
            
            // 按国家统计
            List<Object> countryStats = getCountryStatistics("outlook");
            
            // 按包名统计
            List<Object> pkgStats = getPkgStatistics("outlook");
            
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalDevices", totalDevices);
            statistics.put("statusCounts", statusCounts);
            statistics.put("needRegisterCount", needRegisterCount);
            statistics.put("countryStats", countryStats);
            statistics.put("pkgStats", pkgStats);
            statistics.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", statistics);
            
        } catch (Exception e) {
            log.error("获取设备池统计信息失败", e);
            result.put("success", false);
            result.put("message", "获取统计信息失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取账号库统计信息
     */
    public Map<String, Object> getAccountLibraryStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 总账号数量
            long totalAccounts = accountRepository.selectCount(null);
            
            // 各状态账号数量
            List<Map<String, Object>> statusCounts = accountRepository.countByStatus();
            
            // 养号进度统计
            List<Map<String, Object>> nurtureStats = accountRepository.countByNurtureStatus();
            
            // 刷视频天数分布
            List<Map<String, Object>> videoDaysStats = accountRepository.countVideoDaysDistribution();
            
            // 按国家统计
            List<Object> countryStats = getCountryStatistics("account");
            
            // 按包名统计
            List<Object> pkgStats = getPkgStatistics("account");
            
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalAccounts", totalAccounts);
            statistics.put("statusCounts", statusCounts);
            statistics.put("nurtureStats", nurtureStats);
            statistics.put("videoDaysStats", videoDaysStats);
            statistics.put("countryStats", countryStats);
            statistics.put("pkgStats", pkgStats);
            statistics.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", statistics);
            
        } catch (Exception e) {
            log.error("获取账号库统计信息失败", e);
            result.put("success", false);
            result.put("message", "获取统计信息失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取整体统计概览
     */
    public Map<String, Object> getOverallStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 设备池统计
            Map<String, Object> devicePoolStats = getDevicePoolStatistics();
            @SuppressWarnings("unchecked")
            Map<String, Object> devicePoolData = (Map<String, Object>) devicePoolStats.get("data");
            
            // 账号库统计
            Map<String, Object> accountLibraryStats = getAccountLibraryStatistics();
            @SuppressWarnings("unchecked")
            Map<String, Object> accountLibraryData = (Map<String, Object>) accountLibraryStats.get("data");
            
            // 计算整体指标
            long totalDevices = (Long) devicePoolData.get("totalDevices");
            long totalAccounts = (Long) accountLibraryData.get("totalAccounts");
            int needRegisterCount = (Integer) devicePoolData.get("needRegisterCount");
            
            List<com.cpa.entity.TtAccountData> accountsNeedNurture = accountRepository.findAccountsNeedNurture();
            int needNurtureCount = accountsNeedNurture.size();
            
            List<com.cpa.entity.TtAccountData> nurturedAccounts = accountRepository.findNurturedAccounts();
            int nurturedCount = nurturedAccounts.size();
            
            // 注册转化率
            double registerRate = totalDevices > 0 ? (double) totalAccounts / (totalDevices + totalAccounts) * 100 : 0;
            
            // 养号完成率
            double nurtureRate = totalAccounts > 0 ? (double) nurturedCount / totalAccounts * 100 : 0;
            
            Map<String, Object> overview = new HashMap<>();
            overview.put("totalDevices", totalDevices);
            overview.put("totalAccounts", totalAccounts);
            overview.put("needRegisterCount", needRegisterCount);
            overview.put("needNurtureCount", needNurtureCount);
            overview.put("nurturedCount", nurturedCount);
            overview.put("registerRate", Math.round(registerRate * 100.0) / 100.0);
            overview.put("nurtureRate", Math.round(nurtureRate * 100.0) / 100.0);
            overview.put("devicePoolStats", devicePoolData);
            overview.put("accountLibraryStats", accountLibraryData);
            overview.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", overview);
            
        } catch (Exception e) {
            log.error("获取整体统计概览失败", e);
            result.put("success", false);
            result.put("message", "获取统计概览失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取趋势统计（最近7天）
     */
    public Map<String, Object> getTrendStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            LocalDate today = LocalDate.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            
            Map<String, Object> trends = new HashMap<>();
            
            // 最近7天的数据趋势
            for (int i = 6; i >= 0; i--) {
                LocalDate date = today.minusDays(i);
                String dateStr = date.format(formatter);
                
                // 这里可以查询每天的数据变化
                // 由于现有表结构没有创建时间索引，这里简化处理
                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", dateStr);
                dayData.put("newDevices", 0); // 需要根据实际数据计算
                dayData.put("newAccounts", 0); // 需要根据实际数据计算
                dayData.put("nurturedAccounts", 0); // 需要根据实际数据计算
                
                trends.put(dateStr, dayData);
            }
            
            result.put("success", true);
            result.put("data", trends);
            
        } catch (Exception e) {
            log.error("获取趋势统计失败", e);
            result.put("success", false);
            result.put("message", "获取趋势统计失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取国家统计信息
     */
    private List<Object> getCountryStatistics(String tableType) {
        // 这里需要根据实际的表类型执行不同的查询
        // 简化处理，返回空列表
        return List.of();
    }

    /**
     * 获取包名统计信息
     */
    private List<Object> getPkgStatistics(String tableType) {
        // 这里需要根据实际的表类型执行不同的查询
        // 简化处理，返回空列表
        return List.of();
    }

    /**
     * 获取脚本执行统计
     */
    public Map<String, Object> getScriptExecutionStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 这里可以从Redis或日志中获取脚本执行统计信息
            Map<String, Object> scriptStats = new HashMap<>();
            scriptStats.put("createPhoneExecutions", 0);
            scriptStats.put("registerExecutions", 0);
            scriptStats.put("followExecutions", 0);
            scriptStats.put("watchVideoExecutions", 0);
            scriptStats.put("totalExecutions", 0);
            scriptStats.put("successRate", 0.0);
            scriptStats.put("lastExecutionTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", scriptStats);
            
        } catch (Exception e) {
            log.error("获取脚本执行统计失败", e);
            result.put("success", false);
            result.put("message", "获取脚本执行统计失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取设备利用率统计
     */
    public Map<String, Object> getDeviceUtilizationStatistics() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 计算设备利用率
            long totalDevices = outlookRepository.selectCount(null);
            long totalAccounts = accountRepository.selectCount(null);
            
            List<com.cpa.entity.TtAccountData> accountsNeedNurture = accountRepository.findAccountsNeedNurture();
            int activeAccounts = accountsNeedNurture.size();
            
            double utilizationRate = totalDevices > 0 ? (double) activeAccounts / totalDevices * 100 : 0;
            
            Map<String, Object> utilization = new HashMap<>();
            utilization.put("totalDevices", totalDevices);
            utilization.put("totalAccounts", totalAccounts);
            utilization.put("activeAccounts", activeAccounts);
            utilization.put("utilizationRate", Math.round(utilizationRate * 100.0) / 100.0);
            utilization.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", utilization);
            
        } catch (Exception e) {
            log.error("获取设备利用率统计失败", e);
            result.put("success", false);
            result.put("message", "获取设备利用率统计失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取每日注册脚本统计（基于 tt_account_register）
     * 今日注册数(username not null)、今日2FA成功数、今日留存做2FA数、最近7天趋势
     * @param queryDate 可选，查询基准日，不传则默认今天
     */
    public Map<String, Object> getDailyRegisterOverview(LocalDate queryDate) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            Map<String, Object> data = buildDailyRegisterOverviewData(today);
            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取每日注册概览失败", e);
            result.put("success", false);
            result.put("message", "获取统计失败: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getDailyRegisterTrend(LocalDate queryDate) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            Map<String, Object> data = new HashMap<>();
            data.put("dailyTrend", buildDailyRegisterTrendData(today));
            data.put("lastUpdateTime", LocalDateTime.now());
            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取每日注册趋势失败", e);
            result.put("success", false);
            result.put("message", "获取统计失败: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getDailyRegisterDetail(LocalDate queryDate) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            Map<String, Object> data = new HashMap<>();
            data.put("twofaDetail", buildDailyRegisterDetailData(today));
            data.put("lastUpdateTime", LocalDateTime.now());
            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取每日注册详情失败", e);
            result.put("success", false);
            result.put("message", "获取统计失败: " + e.getMessage());
        }
        return result;
    }

    public Map<String, Object> getDailyRegisterStatistics(LocalDate queryDate) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            Map<String, Object> data = buildDailyRegisterOverviewData(today);
            data.put("dailyTrend", buildDailyRegisterTrendData(today));
            data.put("twofaDetail", buildDailyRegisterDetailData(today));
            data.put("lastUpdateTime", LocalDateTime.now());
            
            result.put("success", true);
            result.put("data", data);
            
        } catch (Exception e) {
            log.error("获取每日注册统计失败", e);
            result.put("success", false);
            result.put("message", "获取统计失败: " + e.getMessage());
        }
        
        return result;
    }

    private Map<String, Object> buildDailyRegisterOverviewData(LocalDate today) {
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(23, 59, 59);
        long todayRegister = ttAccountRegisterRepository.countTodayRegister(todayStart, todayEnd);
        long today2faSuccess = ttAccountRegisterRepository.countToday2faSuccess(todayStart, todayEnd);
        long todayRegisterSuccess = ttAccountRegisterRepository.countTodayRegisterSuccess(todayStart, todayEnd);
        long todayNeedRetention = ttAccountRegisterRepository.countTodayNeedRetention(todayStart, todayEnd);
        double todayRegisterSuccessRate = todayRegister > 0
                ? Math.round((double) todayRegisterSuccess / todayRegister * 10000.0) / 100.0
                : 0;
        double today2faSetupSuccessRate = todayRegisterSuccess > 0
                ? Math.round((double) today2faSuccess / todayRegisterSuccess * 10000.0) / 100.0
                : 0;
        Map<String, Object> data = new HashMap<>();
        data.put("todayRegister", todayRegister);
        data.put("todayRegisterSuccess", todayRegisterSuccess);
        data.put("today2faSuccess", today2faSuccess);
        data.put("todayNeedRetention", todayNeedRetention);
        data.put("todayRegisterSuccessRate", todayRegisterSuccessRate);
        data.put("today2faSetupSuccessRate", today2faSetupSuccessRate);
        data.put("lastUpdateTime", LocalDateTime.now());
        return data;
    }

    private List<Map<String, Object>> buildDailyRegisterTrendData(LocalDate today) {
        LocalDateTime weekStart = today.minusDays(6).atStartOfDay();
        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        List<Map<String, Object>> rawTrend = ttAccountRegisterRepository.countDailyStats(weekStart);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            String dateStr = d.format(fmt);
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", dateStr);
            dayData.put("label", i == 0 ? "今天" : (i == 1 ? "昨天" : d.getMonthValue() + "/" + d.getDayOfMonth()));

            Map<String, Object> found = rawTrend.stream()
                    .filter(m -> {
                        Object v = m.get("stat_date");
                        if (v == null) v = m.get("statDate");
                        return dateStr.equals(String.valueOf(v));
                    })
                    .findFirst().orElse(null);

            long reg = found != null ? getLong(found, "register_cnt") : 0;
            long twofa = found != null ? getLong(found, "twofa_cnt") : 0;
            long ret = found != null ? getLong(found, "retention_cnt") : 0;
            double rate = reg > 0 ? Math.round((double) twofa / reg * 10000.0) / 100.0 : 0;
            dayData.put("register", reg);
            dayData.put("twofa", twofa);
            dayData.put("retention", ret);
            dayData.put("twofaRate", rate);

            LocalDateTime dStart = d.atStartOfDay();
            LocalDateTime dEnd = d.atTime(23, 59, 59);
            List<String> dayTrafficList =
                    ttAccountRegisterRepository.listTrafficDataByDate(dStart, dEnd);
            double dayTraffic = 0.0;
            if (dayTrafficList != null) {
                for (String trafficData : dayTrafficList) {
                    dayTraffic += parseTraffic(trafficData);
                }
            }
            double dayAvg = twofa > 0 ? dayTraffic / twofa : 0.0;
            dayData.put("trafficTotal", Math.round(dayTraffic * 100.0) / 100.0);
            dayData.put("trafficAvg", Math.round(dayAvg * 100.0) / 100.0);
            dailyTrend.add(dayData);
        }
        return dailyTrend;
    }

    private Map<String, Object> buildDailyRegisterDetailData(LocalDate today) {
        LocalDateTime twofaStart = today.atStartOfDay();
        LocalDateTime twofaEnd = today.atTime(23, 59, 59);
        List<com.cpa.entity.TtAccountRegister> twofaList =
                ttAccountRegisterRepository.list2faSuccessByDate(twofaStart, twofaEnd);
        long today2faSuccess = ttAccountRegisterRepository.countToday2faSuccess(twofaStart, twofaEnd);

        Map<String, Long> androidDist = new HashMap<>();
        Map<String, Long> behaviorDist = new HashMap<>();
        Map<String, Long> tiktokDist = new HashMap<>();
        Map<String, Long> countryDist = new HashMap<>();
        Map<String, Long> phoneServerIpDist = new HashMap<>();
        double totalTraffic = 0.0;

        for (com.cpa.entity.TtAccountRegister ar : twofaList) {
            inc(androidDist, normalize(ar.getAndroidVersion(), "未知"));
            inc(behaviorDist, normalize(ar.getBehavior(), "未知"));
            inc(tiktokDist, normalize(ar.getTiktokVersion(), "未知"));
            inc(countryDist, normalize(ar.getCountry(), "未知"));
            inc(phoneServerIpDist, normalize(ar.getPhoneServerIp(), "未知"));
            totalTraffic += parseTraffic(ar.getTrafficData());
        }

        Map<String, Object> twofaDetail = new HashMap<>();
        twofaDetail.put("total2faSuccess", today2faSuccess);
        twofaDetail.put("androidVersionDist", toDistList(androidDist));
        twofaDetail.put("behaviorDist", toDistList(behaviorDist));
        twofaDetail.put("tiktokVersionDist", toDistList(tiktokDist));
        twofaDetail.put("countryDist", toDistList(countryDist));
        twofaDetail.put("phoneServerIpDist", toDistList(phoneServerIpDist));
        twofaDetail.put("trafficTotal", Math.round(totalTraffic * 100.0) / 100.0);
        double avg = today2faSuccess > 0 ? totalTraffic / today2faSuccess : 0.0;
        twofaDetail.put("trafficAvgPerSuccess", Math.round(avg * 100.0) / 100.0);

        // 当天全量流量：trafficData 非空的全部账号（不限定 2FA 成功）
        List<String> allTrafficRows = ttAccountRegisterRepository.listTrafficDataByDate(twofaStart, twofaEnd);
        double trafficTotalAll = 0.0;
        if (allTrafficRows != null) {
            for (String td : allTrafficRows) {
                trafficTotalAll += parseTraffic(td);
            }
        }
        twofaDetail.put("trafficTotalAll", Math.round(trafficTotalAll * 100.0) / 100.0);

        List<Map<String, Object>> byServerHour = ttAccountRegisterRepository.count2faByServerAndHour(twofaStart, twofaEnd);
        Map<String, long[]> hourlyMap = new LinkedHashMap<>();
        for (Map<String, Object> row : byServerHour) {
            String serverIp = normalize(String.valueOf(row.get("server_ip")), "未知");
            int hour = (int) getLong(row, "hour_of_day");
            long cnt = getLong(row, "cnt");
            if (hour < 0 || hour > 23) {
                continue;
            }
            long[] arr = hourlyMap.computeIfAbsent(serverIp, k -> new long[24]);
            arr[hour] += cnt;
        }
        List<Map<String, Object>> serverHourlyList = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : hourlyMap.entrySet()) {
            String serverIp = entry.getKey();
            long[] arr = entry.getValue();
            long totalCnt = 0;
            List<Map<String, Object>> hourly = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                long c = arr[h];
                totalCnt += c;
                Map<String, Object> hourItem = new HashMap<>();
                hourItem.put("hour", String.format("%02d", h));
                hourItem.put("count", c);
                hourly.add(hourItem);
            }
            Map<String, Object> item = new HashMap<>();
            item.put("serverIp", serverIp);
            item.put("total", totalCnt);
            item.put("hourly", hourly);
            serverHourlyList.add(item);
        }
        serverHourlyList.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("total", 0L)).longValue(),
                ((Number) a.getOrDefault("total", 0L)).longValue()
        ));
        twofaDetail.put("serverHourly2fa", serverHourlyList);

        // 注册成功按服务器分时段（用于总览页可查列表）
        List<Map<String, Object>> registerByServerHour =
                ttAccountRegisterRepository.countRegisterSuccessByServerAndHour(twofaStart, twofaEnd);
        // 注册尝试总数（同一口径：created_at 在区间内；用于计算“注册成功率”）
        List<Map<String, Object>> createdByServerHour =
                ttAccountRegisterRepository.countCreatedByServerAndHour(twofaStart, twofaEnd);

        Map<String, long[]> createdHourlyMap = new LinkedHashMap<>();
        for (Map<String, Object> row : createdByServerHour) {
            String serverIp = normalize(String.valueOf(row.get("server_ip")), "未知");
            int hour = (int) getLong(row, "hour_of_day");
            long cnt = getLong(row, "cnt");
            if (hour < 0 || hour > 23) {
                continue;
            }
            long[] arr = createdHourlyMap.computeIfAbsent(serverIp, k -> new long[24]);
            arr[hour] += cnt;
        }
        Map<String, Long> createdTotalByServer = new HashMap<>();
        for (Map.Entry<String, long[]> entry : createdHourlyMap.entrySet()) {
            long total = 0L;
            long[] arr = entry.getValue();
            if (arr != null) {
                for (int h = 0; h < 24; h++) total += arr[h];
            }
            createdTotalByServer.put(entry.getKey(), total);
        }

        Map<String, long[]> registerHourlyMap = new LinkedHashMap<>();
        for (Map<String, Object> row : registerByServerHour) {
            String serverIp = normalize(String.valueOf(row.get("server_ip")), "未知");
            int hour = (int) getLong(row, "hour_of_day");
            long cnt = getLong(row, "cnt");
            if (hour < 0 || hour > 23) {
                continue;
            }
            long[] arr = registerHourlyMap.computeIfAbsent(serverIp, k -> new long[24]);
            arr[hour] += cnt;
        }
        List<Map<String, Object>> registerServerHourlyList = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : registerHourlyMap.entrySet()) {
            String serverIp = entry.getKey();
            long[] arr = entry.getValue();
            long totalCnt = 0;
            long createdTotal = createdTotalByServer.getOrDefault(serverIp, 0L);
            Map<String, Object> item = new HashMap<>();
            item.put("serverIp", serverIp);
            for (int h = 0; h < 24; h++) {
                long c = arr[h];
                totalCnt += c;
                item.put(String.format("h%02d", h), c);
            }
            item.put("total", totalCnt);
            item.put("createdTotal", createdTotal);
            double rate = createdTotal > 0 ? Math.round((double) totalCnt / createdTotal * 10000.0) / 100.0 : 0.0;
            item.put("registerSuccessRate", rate);
            registerServerHourlyList.add(item);
        }
        registerServerHourlyList.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("total", 0L)).longValue(),
                ((Number) a.getOrDefault("total", 0L)).longValue()
        ));
        twofaDetail.put("serverHourlyRegister", registerServerHourlyList);
        return twofaDetail;
    }
    
    private long getLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    private void inc(Map<String, Long> map, String key) {
        map.put(key, map.getOrDefault(key, 0L) + 1L);
    }

    private String normalize(String v, String def) {
        if (v == null || v.trim().isEmpty()) return def;
        return v.trim();
    }

    /**
     * trafficData 解析：从字符串里提取第一个数字，作为流量单位（具体单位依赖上游定义）
     */
    private double parseTraffic(String trafficData) {
        if (trafficData == null) return 0.0;
        String s = trafficData.trim();
        if (s.isEmpty()) return 0.0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(s);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    private List<Map<String, Object>> toDistList(Map<String, Long> src) {
        List<Map<String, Object>> list = new ArrayList<>();
        long total = src.values().stream().mapToLong(Long::longValue).sum();
        src.forEach((k, v) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("value", k);
            item.put("count", v);
            double pct = total > 0 ? Math.round((double) v / total * 10000.0) / 100.0 : 0.0;
            item.put("percent", pct);
            list.add(item);
        });
        list.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("count", 0L),
                (Long) a.getOrDefault("count", 0L)
        ));
        return list;
    }

    private List<Map<String, Object>> toRateDistList(Map<String, Long> blockedMap, Map<String, Long> totalMap) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : totalMap.entrySet()) {
            String key = e.getKey();
            long total = e.getValue() != null ? e.getValue() : 0L;
            if (total <= 0) {
                continue;
            }
            long blocked = blockedMap.getOrDefault(key, 0L);
            double rate = Math.round((double) blocked / total * 10000.0) / 100.0;
            Map<String, Object> item = new HashMap<>();
            item.put("value", key);
            item.put("count", blocked);
            item.put("totalCount", total);
            item.put("percent", rate);
            list.add(item);
        }
        list.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("count", 0L)).longValue(),
                ((Number) a.getOrDefault("count", 0L)).longValue()
        ));
        return list;
    }

    /** null 表示不按国家过滤（含 ALL、空串） */
    private String resolveCountryFilter(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        String c = country.trim();
        if ("ALL".equalsIgnoreCase(c)) {
            return null;
        }
        return c;
    }

    private String displayCountryParam(String country) {
        if (country == null || country.isBlank()) {
            return "ALL";
        }
        return country.trim();
    }

    private static boolean blockedAsOf(com.cpa.entity.TtAccountRegister ar, LocalDateTime asOfEnd) {
        if (ar.getBlockTime() == null) {
            return false;
        }
        return !ar.getBlockTime().isAfter(asOfEnd);
    }

    /**
     * 封号率统计：次日、3天、7天
     * cohort = 2FA 成功日（created_at 当日）；分子 = 截至 {@code queryDate} 当日结束已发生封号（block_time 非空且 ≤ 该时刻）
     * @param queryDate 观察截止日，不传则默认今天
     * @param country 国家，null/空/ALL 表示全量
     */
    public Map<String, Object> getBlockRateStatistics(LocalDate queryDate, String country) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            LocalDateTime asOfEnd = today.atTime(23, 59, 59);
            String countryFilter = resolveCountryFilter(country);

            Map<String, Object> data = new HashMap<>();
            Map<String, Long> blockedAndroidDist = new HashMap<>();
            Map<String, Long> blockedBehaviorDist = new HashMap<>();
            Map<String, Long> blockedTiktokDist = new HashMap<>();
            Map<String, Long> blockedCountryDist = new HashMap<>();
            Map<String, Long> blockedServerIpDist = new HashMap<>();
            Map<String, Map<String, Object>> blockedDetailByPeriod = new HashMap<>();
            data.put("queryDate", today.format(fmt));
            data.put("country", displayCountryParam(country));
            data.put("lastUpdateTime", LocalDateTime.now());

            for (int daysAgo : new int[]{1, 3, 7}) {
                LocalDate baseDate = today.minusDays(daysAgo);
                LocalDateTime start = baseDate.atStartOfDay();
                LocalDateTime end = baseDate.atTime(23, 59, 59);

                long total = ttAccountRegisterRepository.count2faSuccessByDateRangeAndCountry(start, end, countryFilter);
                long blocked = ttAccountRegisterRepository.count2faBlockedByDateRangeAndCountryAndBlockTimeLe(
                        start, end, asOfEnd, countryFilter);
                double rate = total > 0 ? Math.round((double) blocked / total * 10000.0) / 100.0 : 0;

                String key = daysAgo == 1 ? "nextDay" : (daysAgo == 3 ? "threeDay" : "sevenDay");
                Map<String, Object> item = new HashMap<>();
                item.put("baseDate", baseDate.format(fmt));
                item.put("total", total);
                item.put("blocked", blocked);
                item.put("blockRate", rate);
                item.put("label", daysAgo == 1 ? "次日封号率" : (daysAgo == 3 ? "3天封号率" : "7天封号率"));
                data.put(key, item);

                List<com.cpa.entity.TtAccountRegister> all2faList =
                        ttAccountRegisterRepository.list2faSuccessByDateRangeAndCountry(start, end, countryFilter);
                Map<String, Long> periodAndroidDist = new HashMap<>();
                Map<String, Long> periodBehaviorDist = new HashMap<>();
                Map<String, Long> periodTiktokDist = new HashMap<>();
                Map<String, Long> periodCountryDist = new HashMap<>();
                Map<String, Long> periodServerIpDist = new HashMap<>();
                Map<String, Long> periodAndroidTotalDist = new HashMap<>();
                Map<String, Long> periodBehaviorTotalDist = new HashMap<>();
                Map<String, Long> periodTiktokTotalDist = new HashMap<>();
                Map<String, Long> periodCountryTotalDist = new HashMap<>();
                Map<String, Long> periodServerIpTotalDist = new HashMap<>();
                for (com.cpa.entity.TtAccountRegister ar : all2faList) {
                    if (blockedAsOf(ar, asOfEnd)) {
                        inc(blockedAndroidDist, normalize(ar.getAndroidVersion(), "未知"));
                        inc(blockedBehaviorDist, normalize(ar.getBehavior(), "未知"));
                        inc(blockedTiktokDist, normalize(ar.getTiktokVersion(), "未知"));
                        inc(blockedCountryDist, normalize(ar.getCountry(), "未知"));
                        inc(blockedServerIpDist, normalize(ar.getPhoneServerIp(), "未知"));

                        inc(periodAndroidDist, normalize(ar.getAndroidVersion(), "未知"));
                        inc(periodBehaviorDist, normalize(ar.getBehavior(), "未知"));
                        inc(periodTiktokDist, normalize(ar.getTiktokVersion(), "未知"));
                        inc(periodCountryDist, normalize(ar.getCountry(), "未知"));
                        inc(periodServerIpDist, normalize(ar.getPhoneServerIp(), "未知"));
                    }
                    inc(periodAndroidTotalDist, normalize(ar.getAndroidVersion(), "未知"));
                    inc(periodBehaviorTotalDist, normalize(ar.getBehavior(), "未知"));
                    inc(periodTiktokTotalDist, normalize(ar.getTiktokVersion(), "未知"));
                    inc(periodCountryTotalDist, normalize(ar.getCountry(), "未知"));
                    inc(periodServerIpTotalDist, normalize(ar.getPhoneServerIp(), "未知"));
                }
                Map<String, Object> periodDetail = new HashMap<>();
                periodDetail.put("androidVersionDist", toDistList(periodAndroidDist));
                periodDetail.put("behaviorDist", toDistList(periodBehaviorDist));
                periodDetail.put("tiktokVersionDist", toDistList(periodTiktokDist));
                periodDetail.put("countryDist", toDistList(periodCountryDist));
                periodDetail.put("phoneServerIpDist", toDistList(periodServerIpDist));
                periodDetail.put("androidVersionRateDist", toRateDistList(periodAndroidDist, periodAndroidTotalDist));
                periodDetail.put("behaviorRateDist", toRateDistList(periodBehaviorDist, periodBehaviorTotalDist));
                periodDetail.put("tiktokVersionRateDist", toRateDistList(periodTiktokDist, periodTiktokTotalDist));
                periodDetail.put("countryRateDist", toRateDistList(periodCountryDist, periodCountryTotalDist));
                periodDetail.put("phoneServerIpRateDist", toRateDistList(periodServerIpDist, periodServerIpTotalDist));
                blockedDetailByPeriod.put(key, periodDetail);
            }

            Map<String, Object> blockedDetail = new HashMap<>();
            blockedDetail.put("androidVersionDist", toDistList(blockedAndroidDist));
            blockedDetail.put("behaviorDist", toDistList(blockedBehaviorDist));
            blockedDetail.put("tiktokVersionDist", toDistList(blockedTiktokDist));
            blockedDetail.put("countryDist", toDistList(blockedCountryDist));
            blockedDetail.put("phoneServerIpDist", toDistList(blockedServerIpDist));
            data.put("blockedDetail", blockedDetail);
            data.put("blockedDetailByPeriod", blockedDetailByPeriod);

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取封号率统计失败", e);
            result.put("success", false);
            result.put("message", "获取统计失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 封号率最近7天趋势：各点 cohort 为过去7天中某一天注册且 2FA 成功，封号分子统一截至 {@code queryDate} 当日结束
     */
    public Map<String, Object> getBlockRateTrend(LocalDate queryDate, String country) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("MM-dd");
            LocalDateTime asOfEnd = today.atTime(23, 59, 59);
            String countryFilter = resolveCountryFilter(country);

            List<Map<String, Object>> trend = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate baseDate = today.minusDays(i);
                LocalDateTime start = baseDate.atStartOfDay();
                LocalDateTime end = baseDate.atTime(23, 59, 59);

                long total = ttAccountRegisterRepository.count2faSuccessByDateRangeAndCountry(start, end, countryFilter);
                long blocked = ttAccountRegisterRepository.count2faBlockedByDateRangeAndCountryAndBlockTimeLe(
                        start, end, asOfEnd, countryFilter);
                double rate = total > 0 ? Math.round((double) blocked / total * 10000.0) / 100.0 : 0;

                Map<String, Object> item = new HashMap<>();
                item.put("date", baseDate.format(iso));
                item.put("label", baseDate.format(labelFmt));
                item.put("total", total);
                item.put("blocked", blocked);
                item.put("blockRate", rate);
                trend.add(item);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("queryDate", today.format(iso));
            data.put("country", displayCountryParam(country));
            data.put("lastUpdateTime", LocalDateTime.now());
            data.put("trend", trend);

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取封号率趋势统计失败", e);
            result.put("success", false);
            result.put("message", "获取封号率趋势统计失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 回溯矩阵：每行 = 观察日 rowDate，每格 cohort = rowDate - offset；主指标截至 rowDate 日末，副指标「注册次日」截至 cohort+1 日末
     */
    public Map<String, Object> getBlockRateMatrix(LocalDate rangeStart, LocalDate rangeEnd, String country) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (rangeStart == null || rangeEnd == null) {
                result.put("success", false);
                result.put("message", "start 与 end 不能为空");
                return result;
            }
            if (rangeStart.isAfter(rangeEnd)) {
                result.put("success", false);
                result.put("message", "start 不能晚于 end");
                return result;
            }
            String countryFilter = resolveCountryFilter(country);
            DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            int[] offsets = new int[]{1, 3, 7, 14, 21, 30};

            List<Map<String, Object>> rows = new ArrayList<>();
            for (LocalDate rowDate = rangeStart; !rowDate.isAfter(rangeEnd); rowDate = rowDate.plusDays(1)) {
                LocalDateTime asOfRowEnd = rowDate.atTime(23, 59, 59);
                List<Map<String, Object>> cells = new ArrayList<>();
                for (int offset : offsets) {
                    LocalDate cohortDate = rowDate.minusDays(offset);
                    LocalDateTime cStart = cohortDate.atStartOfDay();
                    LocalDateTime cEnd = cohortDate.atTime(23, 59, 59);
                    long total = ttAccountRegisterRepository.count2faSuccessByDateRangeAndCountry(cStart, cEnd, countryFilter);
                    long blockedAsOfRow = ttAccountRegisterRepository.count2faBlockedByDateRangeAndCountryAndBlockTimeLe(
                            cStart, cEnd, asOfRowEnd, countryFilter);
                    double blockRateAsOfRow = total > 0
                            ? Math.round((double) blockedAsOfRow / total * 10000.0) / 100.0
                            : 0.0;
                    LocalDateTime nextDayEnd = cohortDate.plusDays(1).atTime(23, 59, 59);
                    long blockedNextDay = ttAccountRegisterRepository.count2faBlockedByDateRangeAndCountryAndBlockTimeLe(
                            cStart, cEnd, nextDayEnd, countryFilter);
                    double nextDayBlockRate = total > 0
                            ? Math.round((double) blockedNextDay / total * 10000.0) / 100.0
                            : 0.0;

                    Map<String, Object> cell = new HashMap<>();
                    cell.put("offset", offset);
                    cell.put("cohortDate", cohortDate.format(iso));
                    cell.put("total", total);
                    cell.put("blockedAsOfRow", blockedAsOfRow);
                    cell.put("blockRateAsOfRow", blockRateAsOfRow);
                    cell.put("blockedNextDay", blockedNextDay);
                    cell.put("nextDayBlockRate", nextDayBlockRate);
                    cells.add(cell);
                }
                Map<String, Object> row = new HashMap<>();
                row.put("rowDate", rowDate.format(iso));
                row.put("cells", cells);
                rows.add(row);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("start", rangeStart.format(iso));
            data.put("end", rangeEnd.format(iso));
            data.put("country", displayCountryParam(country));
            data.put("rows", rows);
            data.put("lastUpdateTime", LocalDateTime.now());

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取封号率矩阵失败", e);
            result.put("success", false);
            result.put("message", "获取封号率矩阵失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 封号率：按日期返回每天的 total/blocked/blockRate
     * 用于前端“回溯周期矩阵”展示（比如看每个日期往前 3/7/14/21/30 天的封号率）。
     *
     * 注意：这里的 total/blocked 均使用当前接口逻辑：
     * - total：该日 created_at 且 is_2fa_setup_success=1
     * - blocked：该日 created_at 且 is_2fa_setup_success=1 且 block_time IS NOT NULL
     */
    public Map<String, Object> getBlockRateDaily(LocalDate queryDate, int days) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = queryDate != null ? queryDate : LocalDate.now();
            int safeDays = days <= 0 ? 40 : days;
            DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            List<Map<String, Object>> daily = new ArrayList<>();
            // 生成区间：[today-(safeDays-1) ... today]
            for (int i = safeDays - 1; i >= 0; i--) {
                LocalDate baseDate = today.minusDays(i);
                LocalDateTime start = baseDate.atStartOfDay();
                LocalDateTime end = baseDate.atTime(23, 59, 59);

                long total = ttAccountRegisterRepository.count2faSuccessByDate(start, end);
                long blocked = ttAccountRegisterRepository.countBlockedByDate(start, end);
                double rate = total > 0 ? (double) blocked / total * 100D : 0D;
                // 保留更高精度，便于前端矩阵显示对比
                rate = Math.round(rate * 1_000_000_000D) / 1_000_000_000D;

                Map<String, Object> item = new HashMap<>();
                item.put("date", baseDate.format(iso));
                item.put("total", total);
                item.put("blocked", blocked);
                item.put("blockRate", rate);
                daily.add(item);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("queryDate", today.format(iso));
            data.put("days", safeDays);
            data.put("daily", daily);

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取封号率每日数据失败", e);
            result.put("success", false);
            result.put("message", "获取封号率每日数据失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 留存日记：按日期范围查看留存执行记录 + 成功率（分页）
     */
    public Map<String, Object> getRetentionRecords(LocalDate queryDate, int page, int size) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate day = (queryDate != null ? queryDate : LocalDate.now());
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.atTime(23, 59, 59);

            // 最近一天统计：留存脚本/备份执行情况
            Map<String, Object> stat = ttRetentionRecordRepository.statByCreatedBetween(start, end);
            long total = stat != null ? getLong(stat, "total_cnt") : 0;
            long ok = stat != null ? getLong(stat, "script_ok") : 0;
            long backupOk = stat != null ? getLong(stat, "backup_ok") : 0;
            double successRate = total > 0 ? Math.round((double) ok / total * 10000.0) / 100.0 : 0.0;
            double backupRate = total > 0 ? Math.round((double) backupOk / total * 10000.0) / 100.0 : 0.0;

            // 当天 cohort（脚本+备份都成功）的封号情况
            Map<String, Object> cohortStat = ttRetentionRecordRepository.statCohortBlockWithBackup(start, end);
            long cohortTotal = cohortStat != null ? getLong(cohortStat, "total_cnt") : 0;
            long cohortBlocked = cohortStat != null ? getLong(cohortStat, "blocked_cnt") : 0;
            long cohortLogout = cohortStat != null ? getLong(cohortStat, "logout_cnt") : 0;
            double cohortBlockRate = cohortTotal > 0
                    ? Math.round((double) cohortBlocked / cohortTotal * 10000.0) / 100.0
                    : 0.0;

            // 全部 cohort（历史上脚本+备份都成功）的封号情况
            Map<String, Object> allCohortStat = ttRetentionRecordRepository.statAllCohortBlockWithBackup();
            long allCohortTotal = allCohortStat != null ? getLong(allCohortStat, "total_cnt") : 0;
            long allCohortBlocked = allCohortStat != null ? getLong(allCohortStat, "blocked_cnt") : 0;
            long allCohortLogout = allCohortStat != null ? getLong(allCohortStat, "logout_cnt") : 0;
            double allCohortBlockRate = allCohortTotal > 0
                    ? Math.round((double) allCohortBlocked / allCohortTotal * 10000.0) / 100.0
                    : 0.0;

            int offset = (page - 1) * size;
            List<com.cpa.entity.TtRetentionRecord> records =
                    ttRetentionRecordRepository.listByCreatedBetween(start, end, offset, size);

            List<Map<String, Object>> list = new ArrayList<>();
            if (records != null) {
                DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
                for (com.cpa.entity.TtRetentionRecord r : records) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", r.getId());
                    row.put("taskId", r.getTaskId());
                    row.put("phoneId", r.getPhoneId());
                    row.put("phoneServerIp", r.getPhoneServerIp());
                    row.put("accountRegisterId", r.getAccountRegisterId());
                    row.put("gaid", r.getGaid());
                    row.put("scriptSuccess", r.getScriptSuccess());
                    row.put("backupSuccess", r.getBackupSuccess());
                    row.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().format(tsFmt) : null);
                    list.add(row);
                }
            }

            // 账号最终留存状态：按当天全量留存记录关联账号统计（不受分页影响）
            long retention2faSuccess = ttRetentionRecordRepository.countRetention2faSuccessByCreatedBetween(start, end);
            long retentionLogout = ttRetentionRecordRepository.countRetentionLogoutByCreatedBetween(start, end);

            Map<String, Object> data = new HashMap<>();
            data.put("date", day.format(DateTimeFormatter.ISO_LOCAL_DATE));
            data.put("total", total);
            data.put("scriptSuccessCount", ok);
            data.put("backupSuccessCount", backupOk);
            data.put("successRate", successRate);
            data.put("backupRate", backupRate);
            data.put("cohortTotal", cohortTotal);
            data.put("cohortBlocked", cohortBlocked);
            data.put("cohortLogout", cohortLogout);
            data.put("cohortBlockRate", cohortBlockRate);
            data.put("allCohortTotal", allCohortTotal);
            data.put("allCohortBlocked", allCohortBlocked);
            data.put("allCohortLogout", allCohortLogout);
            data.put("allCohortBlockRate", allCohortBlockRate);
            data.put("retention2faSuccess", retention2faSuccess);
            data.put("retentionLogout", retentionLogout);
            data.put("records", list);
            data.put("page", page);
            data.put("size", size);
            data.put("lastUpdateTime", LocalDateTime.now());

            result.put("success", true);
            result.put("data", data);
        } catch (Exception e) {
            log.error("获取留存日记统计失败", e);
            result.put("success", false);
            result.put("message", "获取留存记录失败: " + e.getMessage());
        }
        return result;
    }
}
